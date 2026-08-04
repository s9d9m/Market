package com.marketutils.client.util;

/**
 * Applies Hypixel's Auction House sell tax so profit-based decisions
 * (border color, highlighting, filtering, tooltip text) are based on
 * actual net profit rather than a raw price difference. Every place that
 * needs profit goes through this one calculation so the fee formula
 * lives in exactly one spot.
 */
public final class NetProfitCalculator {

    private static final double AH_SELL_TAX_RATE = 0.03;

    private NetProfitCalculator() {}

    /**
     * Net profit = (estimatedSellPrice * (1 - tax)) - buyPrice.
     */
    public static long netProfit(long buyPrice, long estimatedSellPrice) {
        double netSellPrice = estimatedSellPrice * (1.0 - AH_SELL_TAX_RATE);
        return Math.round(netSellPrice - buyPrice);
    }
}
