package com.marketutils.client.util;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves an item's estimated value from whichever compatible mod's
 * tooltip line is present. All sources are matched in a single pass over
 * the tooltip (see accumulateSourceValues) so a lower-priority source's
 * line is never mistaken for a higher-priority one just because it happens
 * to appear first, without re-scanning the tooltip once per source.
 *
 * Everything below operates on an already-scanned Map<PriceSource, Long>
 * rather than the raw tooltip lines - callers scan the tooltip exactly
 * once (via accumulateSourceValues, one call per line) and then derive
 * AUTO selection / mode resolution from that map for free.
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
     * Checks one already-lowercased, colon-confirmed tooltip line against
     * every price source's labels, filling in "values" for any source that
     * matches and hasn't already been found on an earlier line in this same
     * scan (first-match-per-source-wins, so a later line can never overwrite
     * an earlier one). Call this once per tooltip line to scan for every
     * known source in exactly one pass over the tooltip.
     *
     * @param lowerLine  the line's text, formatting-stripped and lowercased
     * @param afterColon the raw (non-lowercased) text after the line's colon,
     *                   ready for PriceParser.parsePrice
     */
    public static void accumulateSourceValues(String lowerLine, String afterColon, Map<PriceSource, Long> values) {
        for (PriceSource source : PriceSource.VALUES) {
            if (values.containsKey(source)) {
                continue;
            }
            for (String label : source.labels()) {
                if (lowerLine.contains(label)) {
                    long parsed = PriceParser.parsePrice(afterColon);
                    if (parsed > 0L) {
                        values.put(source, parsed);
                    }
                    break;
                }
            }
        }
    }

    /** Fresh, empty map to accumulate into via accumulateSourceValues. */
    public static Map<PriceSource, Long> newValueMap() {
        return new EnumMap<>(PriceSource.class);
    }

    /** Walks AUTO_PRIORITY against an already-scanned value map. */
    public static long findAutoValue(Map<PriceSource, Long> values) {
        for (PriceSource source : AUTO_PRIORITY) {
            long value = values.getOrDefault(source, 0L);
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    /** Returns the source AUTO would pick from an already-scanned value map, or null if none has a value. */
    public static PriceSource findAutoSource(Map<PriceSource, Long> values) {
        for (PriceSource source : AUTO_PRIORITY) {
            if (values.getOrDefault(source, 0L) > 0L) {
                return source;
            }
        }
        return null;
    }

    /**
     * Resolves the estimated value according to the given mode from an
     * already-scanned value map. Forced single-source modes (COFL_MEDIAN,
     * SKYHANNI, CRAFT_PRICE) return only that source's value - if it has
     * none, this returns 0 rather than falling back to another source.
     * DEBUG uses the same value AUTO would pick, since it's meant to show
     * what was selected alongside the raw breakdown, not to change the
     * actual grading.
     */
    public static long resolveValue(Map<PriceSource, Long> values, PricingMode mode) {
        return switch (mode) {
            case AUTO, DEBUG -> findAutoValue(values);
            case COFL_MEDIAN -> values.getOrDefault(PriceSource.COFL_MEDIAN, 0L);
            case SKYHANNI -> values.getOrDefault(PriceSource.SKYHANNI, 0L);
            case CRAFT_PRICE -> values.getOrDefault(PriceSource.CRAFT_PRICE, 0L);
        };
    }
}
