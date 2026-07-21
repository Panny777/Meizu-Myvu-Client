package com.myvu.client.ai;

import com.myvu.client.core.LogBus;

import java.io.IOException;

/** Retries transient HTTP failures once while preserving actionable errors. */
public final class HttpRetry {
    private static final int ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 300;

    private HttpRetry() {}

    public interface Request<T> {
        T execute() throws IOException;
    }

    public static <T> T execute(String service, Request<T> request) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return request.execute();
            } catch (NonRetryableHttpException e) {
                throw e;
            } catch (IOException e) {
                lastError = e;
                if (attempt == ATTEMPTS) break;
                LogBus.warn("HTTP retry: service=" + service + " attempt=" + attempt
                        + " error=" + e.getMessage());
                waitBeforeRetry(service);
            }
        }
        throw lastError;
    }

    public static IOException statusError(int status, String message) {
        if (status >= 400 && status < 500) {
            return new NonRetryableHttpException(message);
        }
        return new IOException(message);
    }

    private static void waitBeforeRetry(String service) throws IOException {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while retrying " + service, e);
        }
    }

    private static final class NonRetryableHttpException extends IOException {
        NonRetryableHttpException(String message) {
            super(message);
        }
    }
}
