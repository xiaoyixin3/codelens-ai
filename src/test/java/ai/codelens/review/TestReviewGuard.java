package ai.codelens.review;

/** Explicitly unfenced guard for offline unit fixtures, never packaged in production. */
public final class TestReviewGuard implements ReviewExecutionGuard {
    public static final TestReviewGuard INSTANCE = new TestReviewGuard();
    @Override public void check() {}
    @Override public void write(Runnable action) { action.run(); }
}
