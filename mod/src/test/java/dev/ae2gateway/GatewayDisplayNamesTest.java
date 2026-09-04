package dev.ae2gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GatewayDisplayNamesTest {
    @Test
    void detectsTranslationKeys() {
        assertTrue(GatewayDisplayNames.looksLikeTranslationKey("material.gtceu.molten_cooperite_ore"));
        assertTrue(GatewayDisplayNames.looksLikeTranslationKey("block.gtceu.casket"));
        assertFalse(GatewayDisplayNames.looksLikeTranslationKey("Sheldonite Dust"));
        assertFalse(GatewayDisplayNames.looksLikeTranslationKey("Raw Sheldonite"));
    }

    @Test
    void humanizesLeafSegments() {
        assertEquals("Molten Cooperite Ore",
                GatewayDisplayNames.humanizeKey("material.gtceu.molten_cooperite_ore"));
        assertEquals("Black Steel Casing",
                GatewayDisplayNames.humanizeKey("block.kubejs.black_steel_casing"));
    }
}
