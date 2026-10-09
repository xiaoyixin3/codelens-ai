package ai.codelens.store;

/** A delivery ID must not be reused for a different signed payload/event. */
public final class DeliveryConflictException extends RuntimeException {
    public DeliveryConflictException() { super("Webhook delivery identity does not match its original payload"); }
}
