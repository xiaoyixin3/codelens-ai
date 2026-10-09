package ai.codelens.github;

/** A remote mutation may have committed. Never replay it as an ordinary job failure. */
public final class PublicationUncertainException extends RuntimeException {
    public PublicationUncertainException(String method, String path, Throwable cause) {
        super("GitHub publication requires reconciliation: " + method + " " + path, cause);
    }
}
