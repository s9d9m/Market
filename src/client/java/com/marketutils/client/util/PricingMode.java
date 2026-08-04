package com.marketutils.client.util;

/**
 * Selects where ProfitRenderer's estimated value comes from. Set via
 * "/marketutils mode <name>" and held in MarketUtilsConfig.
 */
public enum PricingMode {

    /** COFL median -> SkyHanni value -> craft price, first one found wins. */
    AUTO,

    /** COFL median only. If COFL has no value, no value is shown at all. */
    COFL_MEDIAN,

    /** SkyHanni estimated value only. */
    SKYHANNI,

    /** Craft price only. */
    CRAFT_PRICE,

    /** Shows every source's raw value side by side for comparison. */
    DEBUG
}
