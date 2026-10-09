package ai.codelens.review;

import ai.codelens.credentials.CredentialVault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Internal, explicit configuration only. No bean, endpoint or environment activation. */
public final class RecoveryOperatorAuthenticator {
    public static final class Principal {
        private final RecoveryOperatorAuthenticator authority;
        private final String actorHash;
        private Principal(RecoveryOperatorAuthenticator authority, String actorHash) {
            this.authority = authority; this.actorHash = actorHash;
        }
        public String actorHash() { return actorHash; }
        @Override public String toString() { return "[verified recovery principal]"; }
    }
    private final String bearer;
    private final String actorHash;

    public RecoveryOperatorAuthenticator(String configuredSubject, String configuredBearer) {
        if (configuredSubject == null || !configuredSubject.matches("[a-zA-Z0-9._:-]{1,100}")) {
            throw new IllegalArgumentException("Protected operator configuration required");
        }
        try { new CredentialVault(configuredBearer); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Protected operator configuration required"); }
        bearer = configuredBearer;
        actorHash = digest("publication-recovery-operator-v1:" + configuredSubject);
    }
    public Principal authenticate(String presentedBearer) {
        if (presentedBearer == null || presentedBearer.length() > 128
                || !CredentialVault.tokenMatches(presentedBearer, bearer)) throw rejected();
        return new Principal(this, actorHash);
    }
    void requireOwned(Principal principal) {
        if (principal == null || principal.authority != this) throw rejected();
    }
    void requireSeparateKey(String key) {
        try {
            if (MessageDigest.isEqual(java.util.Base64.getDecoder().decode(bearer), java.util.Base64.getDecoder().decode(key))) {
                throw new IllegalArgumentException("Separate approval key required");
            }
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Separate approval key required"); }
    }
    static SecurityException rejected() { return new SecurityException("Recovery authorization rejected"); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Digest unavailable"); }
    }
    @Override public String toString() { return "[protected recovery authenticator]"; }
}
