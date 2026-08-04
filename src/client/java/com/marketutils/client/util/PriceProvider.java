package com.marketutils.client.util;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Resolves an item's estimated value from whichever compatible mod's tooltip
 * line is present, trying sources in priority order and taking the first
 * one that actually has a parseable line:
 *
 *   COFL median > SkyBlocker estimated value > SkyHanni estimated value > craft price
 *
 * Each tier is scanned across the full tooltip independently of the others,
 * so a lower-priority mod's line is never mistaken for a higher-priority
 * one just because it happens to appear first in the list.
 */
public final class PriceProvider {

    private record Tier(String... labels) {}

    // Confirmed against a real in-game COFL tooltip: "Med: ~24,235,554 Vol: 0.8".
    // The "Med:" line and the "Vol:" that follows it on the same line both
    // start with distinct labels before their own colon, and PriceParser only
    // reads up to the first number after the first colon, so the trailing
    // "Vol: 0.8" segment is never mistaken for the median value.
    //
    // Note: COFL also shows its own separate "Estimated Value:" line (a
    // blended figure combining lbin/median/craft cost), which is NOT what
    // this tier targets - this tier is specifically the raw median. Because
    // that blended line's label text is identical to SkyHanni's, the two
    // can't be told apart by text alone if both mods are installed.
    private static final Tier COFL = new Tier("med:");

    private static final Tier SKYBLOCKER = new Tier("est. item value:");

    private static final Tier SKYHANNI = new Tier(
            "estimated item value:",
            "estimated value:",
            "est. value:",
            "est. item value:"
    );

    // "Full Craft Cost:" confirmed from the same real COFL tooltip; "Crafting
    // Price:" confirmed from SkyBlocker's CraftPriceTooltip.java.
    private static final Tier CRAFT_PRICE = new Tier("crafting price:", "craft price:", "full craft cost:");

    private static final Tier[] PRIORITY = { COFL, SKYBLOCKER, SKYHANNI, CRAFT_PRICE };

    private PriceProvider() {}

    /**
     * Scans the tooltip lines for each price source in priority order and
     * returns the first parseable value found. Returns 0 if none match.
     */
    public static long findEstimatedValue(List<Component> lines) {
        for (Tier tier : PRIORITY) {
            long value = scanTier(lines, tier);
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    private static long scanTier(List<Component> lines, Tier tier) {
        for (Component line : lines) {
            String plain = PriceParser.stripFormatting(line.getString());
            String lower = plain.toLowerCase();
            int colon = plain.indexOf(':');
            if (colon == -1) {
                continue;
            }

            for (String label : tier.labels()) {
                if (lower.contains(label)) {
                    long parsed = PriceParser.parsePrice(plain.substring(colon + 1));
                    if (parsed > 0L) {
                        return parsed;
                    }
                }
            }
        }
        return 0L;
    }
}
