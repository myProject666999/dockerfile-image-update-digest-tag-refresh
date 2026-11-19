package com.salesforce.dockerfileimageupdate.digest;

/**
 * Thrown when a digest cannot be resolved from a registry. Carries whether the failure is
 * transient (timeouts, 5xx, rate limiting) and therefore worth a bounded retry, or permanent
 * (not found, authentication failures) in which case retrying is pointless.
 */
public class DigestResolutionException extends Exception {

    private final boolean transientFailure;

    public DigestResolutionException(String message, boolean transientFailure) {
        super(message);
        this.transientFailure = transientFailure;
    }

    public DigestResolutionException(String message, Throwable cause, boolean transientFailure) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    public boolean isTransientFailure() {
        return transientFailure;
    }
}
