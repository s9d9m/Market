package com.marketutils.client.util;

/**
 * Which threshold(s) a slot's net profit/ROI must clear to get highlighted -
 * see MarketUtilsConfig.getMinimumProfitThreshold()/getMinimumRoiPercent().
 * Independent of profitableOnly, which always applies regardless of mode.
 */
public enum FilterMode {
    /** Only the minimum net profit threshold must be met. */
    PROFIT_ONLY,
    /** Only the minimum ROI threshold must be met. */
    ROI_ONLY,
    /** Both the minimum net profit AND minimum ROI thresholds must be met. */
    BOTH
}
