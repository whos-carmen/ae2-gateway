package dev.ae2gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreLogicTest {
    @Test
    void tokensAreDeterministicAndGridScopedWithoutEmbeddingKey() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String token = CoreLogic.opaqueToken("secret", first, "{id:minecraft:diamond}");

        assertEquals(token, CoreLogic.opaqueToken("secret", first, "{id:minecraft:diamond}"));
        assertNotEquals(token, CoreLogic.opaqueToken("secret", second, "{id:minecraft:diamond}"));
        assertFalse(token.contains("diamond"));
    }

    @Test
    void signedCursorRoundTripsAndRejectsTampering() {
        String cursor = CoreLogic.encodeCursor("secret", 42, 500);

        assertEquals(new CoreLogic.Cursor(42, 500), CoreLogic.decodeCursor("secret", cursor));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLogic.decodeCursor("secret", cursor.substring(0, cursor.length() - 1) + "x"));
        assertThrows(IllegalArgumentException.class, () -> CoreLogic.decodeCursor("other", cursor));
    }

    @Test
    void emptyCursorStartsAtZero() {
        assertEquals(new CoreLogic.Cursor(-1, 0), CoreLogic.decodeCursor("secret", null));
    }

    @Test
    void linkCodesUseUnambiguousAlphabetAndExpectedLength() {
        SecureRandom random = new SecureRandom();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String code = CoreLogic.newLinkCode(random);
            assertTrue(code.matches("[A-Z2-9]{8}"));
            assertFalse(code.matches(".*[01IO].*"));
            codes.add(code);
        }
        assertTrue(codes.size() > 95);
    }

    @Test
    void expiryBoundaryIsClosed() {
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        assertTrue(CoreLogic.isExpired(now, now));
        assertTrue(CoreLogic.isExpired(now.minusSeconds(1), now));
        assertFalse(CoreLogic.isExpired(now.plusSeconds(1), now));
    }

    @Test
    void secretComparisonHandlesNullAndDifferences() {
        assertTrue(CoreLogic.constantTimeEquals("same", "same"));
        assertFalse(CoreLogic.constantTimeEquals("same", "different"));
        assertFalse(CoreLogic.constantTimeEquals("same", null));
    }
}
