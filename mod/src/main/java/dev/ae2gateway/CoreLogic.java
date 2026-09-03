package dev.ae2gateway;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CoreLogic {
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private CoreLogic() {
    }

    public static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    public static String opaqueToken(String secret, UUID gridId, String canonicalKey) {
        return "k1." + hmac(secret, gridId + "\n" + canonicalKey);
    }

    public static String encodeCursor(String secret, long revision, int offset) {
        String body = revision + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8))
                + "." + hmac(secret, body);
    }

    public static Cursor decodeCursor(String secret, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(-1, 0);
        }
        try {
            int dot = cursor.indexOf('.');
            if (dot < 1 || dot == cursor.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            String body = new String(Base64.getUrlDecoder().decode(cursor.substring(0, dot)),
                    StandardCharsets.UTF_8);
            if (!constantTimeEquals(hmac(secret, body), cursor.substring(dot + 1))) {
                throw new IllegalArgumentException("invalid cursor");
            }
            String[] parts = body.split(":", -1);
            long revision = Long.parseLong(parts[0]);
            int offset = Integer.parseInt(parts[1]);
            if (parts.length != 2 || revision < 0 || offset < 0) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new Cursor(revision, offset);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    public static String newLinkCode(SecureRandom random) {
        char[] code = new char[8];
        for (int i = 0; i < code.length; i++) {
            code[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(code);
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isExpired(Instant expiresAt, Instant now) {
        return !expiresAt.isAfter(now);
    }

    private static String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    public record Cursor(long revision, int offset) {
    }
}
