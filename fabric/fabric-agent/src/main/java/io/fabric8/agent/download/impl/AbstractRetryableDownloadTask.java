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
package io.fabric8.agent.download.impl;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractRetryableDownloadTask extends AbstractDownloadTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRetryableDownloadTask.class);

    private long scheduleDelay = 250;
    protected int scheduleNbRun = 0;

    private Exception previousException = null;

    public AbstractRetryableDownloadTask(ScheduledExecutorService executorService, String url) {
        super(executorService, url);
    }

    public void run() {
        try {
            try {
                File file = download(previousException);
                setFile(file);
            } catch (IOException e) {
                Retry retry = isRetryable(e);
                if (++scheduleNbRun < retry.getAttempts()) {
                    previousException = e;
                    long delay = (long)(scheduleDelay * 3 / 2 + Math.random() * scheduleDelay / 2);
                    LOGGER.debug("Error downloading " + url + ": " + e.getMessage() + ". " + retry + " in approx " + delay + " ms.");
                    executorService.schedule(this, delay, TimeUnit.MILLISECONDS);
                    scheduleDelay *= 2;
                } else {
                    setException(new IOException("Error downloading " + url, e));
                }
            }
        } catch (Throwable e) {
            setException(new IOException("Error downloading " + url, e));
        }
    }

    protected Retry isRetryable(IOException e) {
        return Retry.DEFAULT_RETRY;
    }

    /**
     * Abstract download operation that may use <em>previous exception</em> as hint for optimized retry
     * @param previousException
     * @return
     * @throws Exception
     */
    protected abstract File download(Exception previousException) throws Exception;

    /**
     * What kind of retry may be attempted
     */
    protected enum Retry {
        /** Each retry would lead to the same result */
        NO_RETRY(0),
        /** It's ok to retry 2, 3 times, but no more */
        QUICK_RETRY(3),
        /** Retry with high expectation of success at some point */
        DEFAULT_RETRY(9);

        private int attempts;

        private Retry(int attempts) {
            this.attempts = attempts;
        }

        /**
         * Returns number of <em>suggested</em> attempts.
         * @return
         */
        public int getAttempts() {
            return attempts;
        }
    }

}
