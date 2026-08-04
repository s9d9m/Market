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

    // COFL's line text is generated server-side by their mod and isn't a
    // fixed string in their client source, so this pattern is unverified -
    // it falls through to the next tier harmlessly until confirmed against
    // the real in-game text.
    private static final Tier COFL = new Tier("median price:", "median:");

    private static final Tier SKYBLOCKER = new Tier("est. item value:");

    private static final Tier SKYHANNI = new Tier(
            "estimated item value:",
            "estimated value:",
            "est. value:",
            "est. item value:"
    );

    private static final Tier CRAFT_PRICE = new Tier("crafting price:", "craft price:");

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
