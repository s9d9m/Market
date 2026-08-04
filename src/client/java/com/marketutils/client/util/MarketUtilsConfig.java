package com.marketutils.client.util;

/**
 * Holds the currently selected pricing mode and highlight filtering
 * settings. In-memory only, resets to defaults on restart - no
 * persistent config file yet.
 */
public final class MarketUtilsConfig {

    /** Sentinel meaning "no minimum profit threshold configured". */
    public static final long NO_MINIMUM_PROFIT = Long.MIN_VALUE;

    private static volatile PricingMode mode = PricingMode.AUTO;
    private static volatile boolean profitableOnly = false;
    private static volatile long minimumProfitThreshold = NO_MINIMUM_PROFIT;

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
}
