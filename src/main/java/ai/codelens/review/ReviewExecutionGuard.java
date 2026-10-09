package ai.codelens.review;

/** Check before external work; fence local writes in a short database transaction. */
public interface ReviewExecutionGuard {
    void check();
    void write(Runnable action);
}
