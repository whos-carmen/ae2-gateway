package dev.ae2gateway;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class IdentityAssertionVerifier {
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("sub", "email", "iat", "exp", "jti", "method", "path");
    private static final int MAX_ASSERTION_LENGTH = 4_096;
    private static final int MAX_PAYLOAD_BYTES = 2_048;
    private static final int ASSERTION_LIFETIME_SECONDS = 30;

    private final byte[] secret;
    private final int maxReplayEntries;
    private final int clockSkewSeconds;
    private final LongSupplier now;
    private final Map<String, Long> replayExpirations = new LinkedHashMap<>();

    IdentityAssertionVerifier(String secret, int maxReplayEntries, int clockSkewSeconds) {
        this(secret, maxReplayEntries, clockSkewSeconds, () -> Instant.now().getEpochSecond());
    }

    IdentityAssertionVerifier(String secret, int maxReplayEntries, int clockSkewSeconds, LongSupplier now) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.maxReplayEntries = maxReplayEntries;
        this.clockSkewSeconds = clockSkewSeconds;
        this.now = now;
    }

    GatewayCoordinator.Identity verify(String assertion, String requestId, String actualMethod, String actualPath)
            throws VerificationException {
        if (assertion == null || assertion.length() > MAX_ASSERTION_LENGTH || assertion.indexOf('=') >= 0) {
            throw invalid();
        }
        int dot = assertion.indexOf('.');
        if (dot < 1 || dot != assertion.lastIndexOf('.') || dot == assertion.length() - 1) {
            throw invalid();
        }
        String encodedPayload = assertion.substring(0, dot);
        String encodedSignature = assertion.substring(dot + 1);
        if (!isBase64Url(encodedPayload) || encodedSignature.length() != 43
                || !isBase64Url(encodedSignature)) {
            throw invalid();
        }

        byte[] suppliedSignature;
        byte[] payload;
        try {
            suppliedSignature = Base64.getUrlDecoder().decode(encodedSignature);
            payload = Base64.getUrlDecoder().decode(encodedPayload);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (suppliedSignature.length != 32 || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw invalid();
        }
        byte[] expectedSignature = hmac(encodedPayload);
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw invalid();
        }

        Claims claims = parseClaims(decodeUtf8(payload));
        validateClaims(claims, requestId, actualMethod, actualPath);
        rememberJti(claims.jti, claims.exp);
        return new GatewayCoordinator.Identity(claims.subject, claims.email);
    }

    synchronized int replayEntryCount() {
        cleanup(now.getAsLong());
        return replayExpirations.size();
    }

    private void validateClaims(Claims claims, String requestId, String actualMethod, String actualPath)
            throws VerificationException {
        if (!safeText(claims.subject, 1, 256) || !safeText(claims.email, 3, 320)
                || !claims.email.contains("@") || claims.jti == null || !claims.jti.matches("[0-9a-f]{32}")
                || !CoreLogic.constantTimeEquals(claims.jti, requestId)
                || !safeText(claims.method, 1, 16) || !claims.method.matches("[A-Z]+")
                || !CoreLogic.constantTimeEquals(claims.method, actualMethod)
                || !safeText(claims.path, 1, 2_048) || !claims.path.startsWith("/")
                || claims.path.indexOf('?') >= 0 || !CoreLogic.constantTimeEquals(claims.path, actualPath)) {
            throw invalid();
        }
        long lifetime;
        try {
            lifetime = Math.subtractExact(claims.exp, claims.iat);
        } catch (ArithmeticException exception) {
            throw invalid();
        }
        long current = now.getAsLong();
        if (lifetime != ASSERTION_LIFETIME_SECONDS
                || claims.iat > current + clockSkewSeconds
                || claims.iat < current - ASSERTION_LIFETIME_SECONDS - clockSkewSeconds
                || claims.exp < current - clockSkewSeconds) {
            throw invalid();
        }
    }

    private synchronized void rememberJti(String jti, long expiresAt) throws VerificationException {
        long current = now.getAsLong();
        cleanup(current);
        if (replayExpirations.containsKey(jti)) {
            throw new VerificationException(401, "identity_assertion_replayed");
        }
        if (replayExpirations.size() >= maxReplayEntries) {
            throw new VerificationException(503, "identity_replay_cache_full");
        }
        replayExpirations.put(jti, expiresAt);
    }

    private void cleanup(long current) {
        replayExpirations.entrySet().removeIf(
                entry -> entry.getValue() < current - clockSkewSeconds);
    }

    private Claims parseClaims(String json) throws VerificationException {
        String subject = null;
        String email = null;
        String jti = null;
        String method = null;
        String path = null;
        Long iat = null;
        Long exp = null;
        Set<String> seen = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!REQUIRED_FIELDS.contains(name) || !seen.add(name)) {
                    throw invalid();
                }
                switch (name) {
                    case "sub" -> subject = strictString(reader);
                    case "email" -> email = strictString(reader);
                    case "jti" -> jti = strictString(reader);
                    case "method" -> method = strictString(reader);
                    case "path" -> path = strictString(reader);
                    case "iat" -> iat = strictLong(reader);
                    case "exp" -> exp = strictLong(reader);
                    default -> throw invalid();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !seen.equals(REQUIRED_FIELDS)) {
                throw invalid();
            }
        } catch (IOException | IllegalStateException | NumberFormatException exception) {
            throw invalid();
        }
        return new Claims(subject, email, iat, exp, jti, method, path);
    }

    private static String strictString(JsonReader reader) throws IOException, VerificationException {
        if (reader.peek() != JsonToken.STRING) {
            throw invalid();
        }
        return reader.nextString();
    }

    private static long strictLong(JsonReader reader) throws IOException, VerificationException {
        if (reader.peek() != JsonToken.NUMBER) {
            throw invalid();
        }
        String raw = reader.nextString();
        if (!raw.matches("-?(0|[1-9][0-9]*)")) {
            throw invalid();
        }
        return Long.parseLong(raw);
    }

    private byte[] hmac(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    private static String decodeUtf8(byte[] payload) throws VerificationException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw invalid();
        }
    }

    private static boolean safeText(String value, int min, int max) {
        return value != null && value.length() >= min && value.length() <= max
                && value.chars().noneMatch(ch -> ch < 0x20 || ch == 0x7f);
    }

    private static boolean isBase64Url(String value) {
        return !value.isEmpty() && value.chars().allMatch(ch ->
                ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z'
                        || ch >= '0' && ch <= '9' || ch == '-' || ch == '_');
    }

    private static VerificationException invalid() {
        return new VerificationException(401, "invalid_identity_assertion");
    }

    private record Claims(String subject, String email, long iat, long exp, String jti, String method,
            String path) {
    }

    static final class VerificationException extends Exception {
        final int status;
        final String code;

        VerificationException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }
}
