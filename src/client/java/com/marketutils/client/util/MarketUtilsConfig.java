package com.marketutils.client.util;

/**
 * Holds the currently selected pricing mode and highlight filtering
 * settings. In-memory only, resets to defaults on restart - no
 * persistent config file yet.
 */
public final class MarketUtilsConfig {

    /** Sentinel meaning "no minimum profit threshold configured". */
    public static final long NO_MINIMUM_PROFIT = Long.MIN_VALUE;

    /** Sentinel meaning "no minimum ROI threshold configured". */
    public static final double NO_MINIMUM_ROI = Double.NEGATIVE_INFINITY;

    private static volatile PricingMode mode = PricingMode.AUTO;
    private static volatile boolean profitableOnly = false;
    private static volatile long minimumProfitThreshold = NO_MINIMUM_PROFIT;
    private static volatile double minimumRoiPercent = NO_MINIMUM_ROI;

    // Defaults to PROFIT_ONLY so a fresh install behaves exactly like
    // before the ROI filter existed - the ROI threshold is simply never
    // checked until the player switches mode or sets one.
    private static volatile FilterMode filterMode = FilterMode.PROFIT_ONLY;

    private MarketUtilsConfig() {}

    public static PricingMode getMode() {
        return mode;
    }

    public static void setMode(PricingMode newMode) {
        mode = newMode;
    }

    /** If true, items with net profit <= 0 never get a border, regardless of pricing mode. */
    public static boolean isProfitableOnly() {
        return profitableOnly;
    }

    public static void setProfitableOnly(boolean value) {
        profitableOnly = value;
    }

    /** Items with net profit below this are never highlighted. NO_MINIMUM_PROFIT means the filter is off. */
    public static long getMinimumProfitThreshold() {
        return minimumProfitThreshold;
    }

    public static void setMinimumProfitThreshold(long value) {
        minimumProfitThreshold = value;
    }

    /** Items with ROI (net profit / buy price * 100) below this are never highlighted. NO_MINIMUM_ROI means the filter is off. */
    public static double getMinimumRoiPercent() {
        return minimumRoiPercent;
    }

    public static void setMinimumRoiPercent(double value) {
        minimumRoiPercent = value;
    }

    /** Which of minimumProfitThreshold/minimumRoiPercent must be met for an item to be highlighted. */
    public static FilterMode getFilterMode() {
        return filterMode;
    }

    public static void setFilterMode(FilterMode value) {
        filterMode = value;
    }
}
