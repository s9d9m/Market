package com.marketutils.client.util;

import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an item's estimated value from whichever compatible mod's
 * tooltip line is present. Each PriceSource is scanned across the full
 * tooltip independently of the others, so a lower-priority source's line
 * is never mistaken for a higher-priority one just because it happens to
 * appear first in the list.
 *
 * AUTO priority order: COFL median -> SkyHanni value -> craft price.
 * SkyBlocker and any future source are deliberately left out of this list
 * for now - adding one later is just inserting it into AUTO_PRIORITY plus
 * a PriceSource entry, nothing else changes.
 */
public final class PriceProvider {

    private static final PriceSource[] AUTO_PRIORITY = {
            PriceSource.COFL_MEDIAN,
            PriceSource.SKYHANNI,
            PriceSource.CRAFT_PRICE
    };

    private PriceProvider() {}

    /**
     * Scans the tooltip for a single source's label and returns its value,
     * or 0 if that source has no matching line.
     */
    public static long findValue(List<Component> lines, PriceSource source) {
        for (Component line : lines) {
            String plain = PriceParser.stripFormatting(line.getString());
            String lower = plain.toLowerCase();
            int colon = plain.indexOf(':');
            if (colon == -1) {
                continue;
            }

            for (String label : source.labels()) {
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

    /**
     * Scans the tooltip for every known source at once, for DEBUG mode's
     * side-by-side comparison.
     */
    public static Map<PriceSource, Long> findAllValues(List<Component> lines) {
        Map<PriceSource, Long> values = new EnumMap<>(PriceSource.class);
        for (PriceSource source : PriceSource.values()) {
            values.put(source, findValue(lines, source));
        }
        return values;
    }

    /** Walks AUTO_PRIORITY and returns the first source with a value. */
    public static long findAutoValue(List<Component> lines) {
        for (PriceSource source : AUTO_PRIORITY) {
            long value = findValue(lines, source);
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    /** Returns the source AUTO would pick, or null if none has a value. */
    public static PriceSource findAutoSource(List<Component> lines) {
        for (PriceSource source : AUTO_PRIORITY) {
            if (findValue(lines, source) > 0L) {
                return source;
            }
        }
        return null;
    }

    /**
     * Resolves the estimated value according to the given mode. Forced
     * single-source modes (COFL_MEDIAN, SKYHANNI, CRAFT_PRICE) return only
     * that source's value - if it has none, this returns 0 rather than
     * falling back to another source. DEBUG uses the same value AUTO would
     * pick, since it's meant to show what was selected alongside the raw
     * breakdown, not to change the actual grading.
     */
    public static long resolveValue(List<Component> lines, PricingMode mode) {
        return switch (mode) {
            case AUTO, DEBUG -> findAutoValue(lines);
            case COFL_MEDIAN -> findValue(lines, PriceSource.COFL_MEDIAN);
            case SKYHANNI -> findValue(lines, PriceSource.SKYHANNI);
            case CRAFT_PRICE -> findValue(lines, PriceSource.CRAFT_PRICE);
        };
    }
}
