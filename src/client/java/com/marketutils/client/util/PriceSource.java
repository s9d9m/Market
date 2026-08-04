package com.marketutils.client.util;

/**
 * A single price value a tooltip line can be scanned for. Each source is
 * independent of the others - adding a new one (e.g. SkyBlocker later) is
 * just a new enum entry plus its label text, with no other code changes.
 */
public enum PriceSource {

    COFL_MEDIAN("COFL Median", "med:"),
    SKYHANNI("SkyHanni Value", "estimated item value:", "estimated value:", "est. value:", "est. item value:"),
    CRAFT_PRICE("Craft Price", "crafting price:", "craft price:");

    /**
     * enum.values() allocates a fresh array on every call - cached once here
     * since this is walked once per tooltip LINE during scanning (see
     * PriceProvider.accumulateSourceValues), not just once per item.
     */
    public static final PriceSource[] VALUES = values();

    private final String displayName;
    private final String[] labels;

    PriceSource(String displayName, String... labels) {
        this.displayName = displayName;
        this.labels = labels;
    }

    public String displayName() {
        return displayName;
    }

    String[] labels() {
        return labels;
    }
}
