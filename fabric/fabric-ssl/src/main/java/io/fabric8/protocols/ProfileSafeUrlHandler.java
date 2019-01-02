/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.protocols;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import org.osgi.framework.BundleContext;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProfileSafeUrlHandler extends AbstractURLStreamHandlerService {

    private static final String SYNTAX = "profile:<resource name>";

    public static final Logger LOGGER = LoggerFactory.getLogger(ProfileSafeUrlHandler.class);

    // sleeps between attempts to delegate to internal profile2: URI handler. total sleep: 40s
    static int[] SLEEPS = new int[] { 1000, 1000, 2000, 2000, 2000, 4000, 4000, 4000, 5000, 5000, 5000, 5000 };
    static int ADDITIONAL_SLEEP = 5000;

    private BundleContext context;
    private int maxWait = 0;

    public ProfileSafeUrlHandler(BundleContext context) {
        this.context = context;

        int defaultTimeout = 0;
        for (int s : SLEEPS) {
            defaultTimeout += s;
        }

        String timeout = context.getProperty("profile.url.timeout");
        if (timeout != null && !"".equals(timeout)) {
            try {
                maxWait = Integer.parseInt(timeout);
                maxWait *= 1000;
            } catch (NumberFormatException e) {
                maxWait = defaultTimeout;
                LOGGER.warn("Incorrect value for \"profile.url.timeout\" property, assuming + " + defaultTimeout + "s.");
            }
        } else {
            maxWait = defaultTimeout;
        }
    }

    @Override
    public URLConnection openConnection(URL url) throws IOException {
        return new Connection(url);
    }

    private class Connection extends URLConnection {

        public Connection(URL url) throws MalformedURLException {
            super(url);
            if (url.getPath() == null || url.getPath().trim().length() == 0) {
                throw new MalformedURLException("Path can not be null or empty. Syntax: " + SYNTAX);
            }
            if ((url.getHost() != null && url.getHost().length() > 0) || url.getPort() != -1) {
                throw new MalformedURLException("Unsupported host/port in profile url");
            }
        }

        @Override
        public void connect() throws IOException {
        }

        @Override
        public InputStream getInputStream() throws IOException {
            // let's hope the internal, ZK-dependent io.fabric8.service.ProfileUrlHandler will be
            // available soon
            int count = 0;
            int totalTimeSlept = 0;
            IOException lastException = null;
            while ((count < SLEEPS.length) && (totalTimeSlept < maxWait)) {
                try {
                    if (count == 0) {
                        LOGGER.debug("Resolving {} (max wait = " + maxWait + ")", url);
                    } else {
                        LOGGER.debug("Resolving {}, attempt {}", url, count + 1);
                    }
                    return new URL("profile2:" + url.toString().substring(8)).openStream();
                } catch (MalformedURLException e) {
                    lastException = e;
                } catch (IllegalStateException e) {
                    if (e.getMessage() != null && e.getMessage().equals("Unknown protocol: profile2")) {
                        lastException = new IOException(e);
                    } else {
                        LOGGER.error("Caught IllegalStateException: " + e.getMessage(), e);
                        throw e;
                    }
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().startsWith("URL [profile2:")) {
                        lastException = e;
                    } else {
                        LOGGER.error("Caught IOException: " + e.getMessage(), e);
                        throw e;
                    }
                }
                try {
                    if (count < SLEEPS.length) {
                        Thread.sleep(SLEEPS[count]);
                        totalTimeSlept += SLEEPS[count];
                    } else {
                        Thread.sleep(ADDITIONAL_SLEEP);
                        totalTimeSlept += ADDITIONAL_SLEEP;
                    }
                    count++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Thread interrupted while trying to resolve " + url);
                }
            }

            if (lastException != null) {
                throw lastException;
            } else {
                throw new MalformedURLException("Can't resolve " + url + " after " + count + " attempts");
            }
        }
    }

}
