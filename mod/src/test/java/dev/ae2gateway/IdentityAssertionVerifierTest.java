package dev.ae2gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class IdentityAssertionVerifierTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String JTI = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsExactSidecarContract() throws Exception {
        IdentityAssertionVerifier verifier = verifier(1_000);
        String assertion = sign(claims(JTI, 1_000, 1_030, "POST", "/api/v1/grids/id/plans"));

        GatewayCoordinator.Identity identity =
                verifier.verify(assertion, JTI, "POST", "/api/v1/grids/id/plans");

        assertEquals("subject-123", identity.subject());
        assertEquals("user@example.test", identity.email());
        assertEquals(1, verifier.replayEntryCount());
    }

    @Test
    void rejectsBadSignatureAndNonCanonicalEncoding() {
        IdentityAssertionVerifier verifier = verifier(1_000);
        String assertion = sign(claims(JTI, 1_000, 1_030, "GET", "/api/v1/grids"));

        assertInvalid(verifier, assertion.substring(0, assertion.length() - 1) + "x",
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier, assertion + "=", JTI, "GET", "/api/v1/grids");
    }

    @Test
    void rejectsMethodPathAndRequestIdMismatch() {
        String assertion = sign(claims(JTI, 1_000, 1_030, "GET", "/api/v1/grids"));

        assertInvalid(verifier(1_000), assertion, JTI, "POST", "/api/v1/grids");
        assertInvalid(verifier(1_000), assertion, JTI, "GET", "/api/v1/identity");
        assertInvalid(verifier(1_000), assertion, "ffffffffffffffffffffffffffffffff",
                "GET", "/api/v1/grids");
    }

    @Test
    void rejectsWrongLifetimeFutureAndExpiredClaims() {
        assertInvalid(verifier(1_000), sign(claims(JTI, 1_000, 1_031, "GET", "/api/v1/grids")),
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(claims(JTI, 1_006, 1_036, "GET", "/api/v1/grids")),
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(claims(JTI, 964, 994, "GET", "/api/v1/grids")),
                JTI, "GET", "/api/v1/grids");
    }

    @Test
    void acceptsConfiguredClockSkewBoundaries() throws Exception {
        verifier(1_000).verify(sign(claims(JTI, 1_005, 1_035, "GET", "/api/v1/grids")),
                JTI, "GET", "/api/v1/grids");
        verifier(1_000).verify(sign(claims(JTI, 965, 995, "GET", "/api/v1/grids")),
                JTI, "GET", "/api/v1/grids");
    }

    @Test
    void rejectsUnknownMissingDuplicateAndWrongTypeFields() {
        String base = claims(JTI, 1_000, 1_030, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.substring(0, base.length() - 1) + ",\"extra\":true}"),
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.replace("\"email\":\"user@example.test\",", "")),
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.replace("\"sub\":\"subject-123\"",
                        "\"sub\":\"subject-123\",\"sub\":\"duplicate\"")),
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.replace("\"iat\":1000", "\"iat\":\"1000\"")),
                JTI, "GET", "/api/v1/grids");
    }

    @Test
    void rejectsInvalidFieldLengthsAndFormats() {
        String base = claims(JTI, 1_000, 1_030, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.replace("subject-123", "")),
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.replace("user@example.test", "not-an-email")),
                JTI, "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.replace(JTI, JTI.toUpperCase())),
                JTI.toUpperCase(), "GET", "/api/v1/grids");
        assertInvalid(verifier(1_000), sign(base.replace("/api/v1/grids", "/api/v1/grids?x=1")),
                JTI, "GET", "/api/v1/grids?x=1");
    }

    @Test
    void rejectsReplayAndFailsClosedAtCapacityUntilCleanup() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        IdentityAssertionVerifier verifier = new IdentityAssertionVerifier(SECRET, 2, 5, now::get);
        String secondJti = "11111111111111111111111111111111";
        String thirdJti = "22222222222222222222222222222222";
        String first = sign(claims(JTI, 1_000, 1_030, "GET", "/api/v1/grids"));

        verifier.verify(first, JTI, "GET", "/api/v1/grids");
        IdentityAssertionVerifier.VerificationException replay = assertThrows(
                IdentityAssertionVerifier.VerificationException.class,
                () -> verifier.verify(first, JTI, "GET", "/api/v1/grids"));
        assertEquals("identity_assertion_replayed", replay.code);

        verifier.verify(sign(claims(secondJti, 1_000, 1_030, "GET", "/api/v1/grids")),
                secondJti, "GET", "/api/v1/grids");
        IdentityAssertionVerifier.VerificationException full = assertThrows(
                IdentityAssertionVerifier.VerificationException.class,
                () -> verifier.verify(sign(claims(thirdJti, 1_000, 1_030, "GET", "/api/v1/grids")),
                        thirdJti, "GET", "/api/v1/grids"));
        assertEquals(503, full.status);

        now.set(1_040);
        verifier.verify(sign(claims(thirdJti, 1_040, 1_070, "GET", "/api/v1/grids")),
                thirdJti, "GET", "/api/v1/grids");
        assertEquals(1, verifier.replayEntryCount());
    }

    private static IdentityAssertionVerifier verifier(long now) {
        return new IdentityAssertionVerifier(SECRET, 16, 5, () -> now);
    }

    private static void assertInvalid(IdentityAssertionVerifier verifier, String assertion, String requestId,
            String method, String path) {
        IdentityAssertionVerifier.VerificationException exception = assertThrows(
                IdentityAssertionVerifier.VerificationException.class,
                () -> verifier.verify(assertion, requestId, method, path));
        assertEquals(401, exception.status);
    }

    private static String claims(String jti, long iat, long exp, String method, String path) {
        return "{\"sub\":\"subject-123\",\"email\":\"user@example.test\",\"iat\":" + iat
                + ",\"exp\":" + exp + ",\"jti\":\"" + jti + "\",\"method\":\"" + method
                + "\",\"path\":\"" + path + "\"}";
    }

    private static String sign(String json) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
            return payload + "." + signature;
        } catch (GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }
}
