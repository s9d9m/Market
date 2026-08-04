package com.marketutils.client.util;

/**
 * Holds the currently selected pricing mode. In-memory only, resets to
 * AUTO on restart - no persistent config file yet.
 */
public final class MarketUtilsConfig {

    private static volatile PricingMode mode = PricingMode.AUTO;

    private MarketUtilsConfig() {}

    public static PricingMode getMode() {
        return mode;
    }

    public static void setMode(PricingMode newMode) {
        mode = newMode;
    }
}
