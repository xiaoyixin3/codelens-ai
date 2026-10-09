package ai.codelens.store;

/** A stale or unverifiable owner must not retry, finish, or publish this job. */
public final class LeaseLostException extends RuntimeException {
    public LeaseLostException() { super("Review job lease is no longer owned"); }
    public LeaseLostException(Throwable cause) { super("Review job lease could not be verified", cause); }
}
