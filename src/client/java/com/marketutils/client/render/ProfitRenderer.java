package com.marketutils.client.render;

import com.marketutils.client.util.MarketUtilsConfig;
import com.marketutils.client.util.NetProfitCalculator;
import com.marketutils.client.util.PriceParser;
import com.marketutils.client.util.PriceProvider;
import com.marketutils.client.util.PriceSource;
import com.marketutils.client.util.PricingMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates whether auction items are worth buying by comparing listing price
 * to an estimated value sourced via PriceProvider, according to whichever
 * PricingMode is currently selected in MarketUtilsConfig (see
 * "/marketutils mode"). Renders a colored BORDER around slots (so SkyHanni's
 * rarity backgrounds remain visible) and appends debug text to tooltips.
 *
 * Caching strategy: every visible slot gets evaluated and cached by slot
 * index as soon as the AH screen renders it. Each slot's FIRST evaluation
 * this scan cycle gets a much higher per-frame budget
 * (MAX_FIRST_PASS_EVALUATIONS_PER_FRAME) than retries, so a freshly
 * opened/changed page fills in within a few frames instead of trickling in
 * over ~18 - not a single uncapped pass, since getTooltipLines() also runs
 * every other mod's tooltip logic, and one item that's slow to resolve
 * (e.g. COFL falling back to fuzzy matching) would otherwise stall the
 * whole frame instead of just delaying its own small batch. RETRIES of a
 * slot that came back empty (waiting on an async source like COFL) stay at
 * the much lower MAX_EVALUATIONS_PER_FRAME, so items nothing ever prices
 * don't get re-evaluated every frame forever. Hovering an item
 * only ever reads the cache - it never re-evaluates or re-parses tooltip
 * text on its own. The currently-hovered slot is tracked directly from
 * Minecraft's own hoveredSlot field (via the mixin), not by matching item
 * display names, so results can never be shown for the wrong slot.
 *
 * Color scale is percentage-based:
 *   BIN far below estimated value  -> deep green border  (great deal)
 *   BIN slightly below             -> yellow-green border
 *   BIN roughly equal              -> yellow border      (neutral)
 *   BIN slightly above             -> orange border
 *   BIN far above estimated value  -> deep red border    (bad deal)
 */
public final class ProfitRenderer {

    private static final int MAX_EVALUATIONS_PER_FRAME = 3;

    // First-time evaluations get a much higher budget than retries so a
    // fresh page fills in quickly, but NOT fully uncapped: getTooltipLines()
    // fires every other mod's tooltip logic too, and an item COFL can't
    // cleanly match (falls back to its slower fuzzy-match/estimate path)
    // can take noticeably longer than a normal lookup. Evaluating all 54
    // slots synchronously in one frame meant one slow item stalled the
    // whole frame; capping it bounds that to one small batch instead.
    private static final int MAX_FIRST_PASS_EVALUATIONS_PER_FRAME = 12;

    private static int evaluationsThisFrame = 0;
    private static int firstPassEvaluationsThisFrame = 0;
    private static long lastFrameStartNanos = 0;

    private static boolean evaluating = false;

    private static final Map<Integer, SlotProfitEntry> SLOT_CACHE = new HashMap<>();

    /** Every slot index the current screen has rendered at least once, whether it had an item or not - used for scan-progress reporting. */
    private static final Set<Integer> OBSERVED_SLOTS = new HashSet<>();
    private static final int TOTAL_AH_SLOTS = 54;

    private static volatile int hoveredSlotIndex = -1;
    private static Object currentScreenIdentity;

    private static Object revisionTrackedMenu;
    private static int lastSeenRevision = Integer.MIN_VALUE;

    private static volatile long scanStartNanos = System.nanoTime();
    private static volatile long scanCompletedNanos = 0L;
    private static volatile boolean scanComplete = false;

    private static final int BORDER_THICKNESS = 2;

    private static final double NEUTRAL_BAND_PERCENT = 0.03;

    // 50%+ net margins essentially never happen on real AH flips (that
    // would mean buying at under half real value), so a 0-50% scale
    // squashed every realistic flip within a few percent of the neutral
    // edge, making genuinely different-quality flips look nearly
    // identical - and after switching profit calculations to NET (post-tax)
    // margins, which run a few points lower than raw margins across the
    // board, that compression got worse. 20% keeps the gradient meaningful
    // across the range real flips actually fall in.
    private static final double MAX_SCALE_PERCENT = 0.20;

    private static final int NEUTRAL_ALPHA = 130;
    private static final int NEUTRAL_R = 0xE0;
    private static final int NEUTRAL_G = 0xD0;
    private static final int NEUTRAL_B = 0x00;

    private static final int DEEP_GREEN_ALPHA = 210;
    private static final int DEEP_GREEN_R = 20;
    private static final int DEEP_GREEN_G = 220;
    private static final int DEEP_GREEN_B = 20;

    private static final int DEEP_RED_ALPHA = 210;
    private static final int DEEP_RED_R = 220;
    private static final int DEEP_RED_G = 20;
    private static final int DEEP_RED_B = 20;

    private record SlotProfitEntry(
            String fingerprint,
            long price,
            long estimatedValue,
            int tintColor,
            Map<PriceSource, Long> sourceValues,
            PriceSource autoSelectedSource
    ) {}

    private ProfitRenderer() {}

    /**
     * Called from the render mixin for every visible slot, every frame,
     * regardless of hover. This is what drives the eager full-page scan:
     * each new (or invalidated) slot gets evaluated once, throttled to
     * MAX_EVALUATIONS_PER_FRAME globally, and cached by slot index.
     * Draws a 2px colored border on top of everything at the slot edges,
     * leaving the center visible for rarity backgrounds from SkyHanni and
     * the item icon.
     */
    public static void renderSlotBackground(GuiGraphicsExtractor guiGraphics, Slot slot) {
        if (slot == null) {
            return;
        }

        OBSERVED_SLOTS.add(slot.index);
        checkScanComplete();

        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        if (evaluating) {
            return;
        }

        resetFrameCounterIfNewFrame();

        int slotIndex = slot.index;
        String fingerprint = buildFingerprint(stack);
        SlotProfitEntry cached = SLOT_CACHE.get(slotIndex);

        if (cached != null && !cached.fingerprint().equals(fingerprint)) {
            // This slot's item changed without the screen being rebuilt
            // (e.g. an in-place AH page swap that reuses the same screen
            // instance) - the whole page just changed, not just this slot,
            // so restart the full scan rather than patch one entry.
            clearCache();
            cached = null;
            OBSERVED_SLOTS.add(slotIndex);
        }

        // Only trust a cached result if price data was actually found - NOT
        // just tintColor != 0, since a fully-resolved item can legitimately
        // have tintColor == 0 now (filtered out by the profitable-only or
        // minimum-profit settings). Checking price/estimatedValue directly
        // keeps that case correctly treated as stable/cached, while a slot
        // that genuinely found nothing yet (e.g. COFL's median not loaded
        // yet) still keeps retrying on later frames.
        if (cached != null && cached.price() > 0L && cached.estimatedValue() > 0L) {
            renderBorder(guiGraphics, slot, cached.tintColor());
            return;
        }

        // A slot's first-ever evaluation this scan cycle gets a much higher
        // per-frame budget than retries, so a freshly opened/changed page
        // fills in within a few frames instead of trickling in over ~18 -
        // but capped, not fully uncapped, so one item that's slow to
        // resolve (e.g. COFL falling back to fuzzy matching) only delays
        // its own small batch instead of stalling the whole frame. RETRIES
        // of a slot that already came back empty stay at the much lower
        // MAX_EVALUATIONS_PER_FRAME - otherwise an item no price source
        // tracks would get re-evaluated every single frame forever.
        boolean isFirstAttempt = cached == null;
        if (isFirstAttempt) {
            if (firstPassEvaluationsThisFrame >= MAX_FIRST_PASS_EVALUATIONS_PER_FRAME) {
                return;
            }
        } else if (evaluationsThisFrame >= MAX_EVALUATIONS_PER_FRAME) {
            return;
        }

        evaluating = true;
        try {
            SlotProfitEntry entry = evaluateFromTooltip(stack, fingerprint);
            SLOT_CACHE.put(slotIndex, entry);
            if (isFirstAttempt) {
                firstPassEvaluationsThisFrame++;
            } else {
                evaluationsThisFrame++;
            }
            renderBorder(guiGraphics, slot, entry.tintColor());
        } finally {
            evaluating = false;
        }
    }

    /**
     * Called from the ItemTooltipCallback registered in MarketutilsClient.
     * Purely a cache read keyed by the currently-hovered slot index (tracked
     * by the mixin from Minecraft's own hoveredSlot field) - it never
     * evaluates or parses tooltip text itself, and never calls
     * getTooltipLines() (that would recurse infinitely, since this callback
     * runs from inside the tooltip build it would be asking for again). If
     * that slot hasn't been scanned yet, nothing is shown rather than
     * falling back to a partial/unreliable parse.
     */
    public static void appendTooltipText(ItemStack stack, List<Component> lines) {
        if (stack == null || stack.isEmpty() || lines == null) {
            return;
        }

        if (evaluating) {
            return;
        }

        SlotProfitEntry entry = hoveredSlotIndex >= 0 ? SLOT_CACHE.get(hoveredSlotIndex) : null;

        if (entry != null && entry.price() > 0L && entry.estimatedValue() > 0L) {
            long netProfit = NetProfitCalculator.netProfit(entry.price(), entry.estimatedValue());
            double profitPercent = (double) netProfit / (double) entry.estimatedValue() * 100.0;

            String text;
            if (profitPercent > NEUTRAL_BAND_PERCENT * 100.0) {
                text = String.format(
                        "\u00A7aWorth it! %.1f%% below value (%s coins profit)",
                        profitPercent, formatNumber(netProfit)
                );
            } else if (profitPercent < -(NEUTRAL_BAND_PERCENT * 100.0)) {
                text = String.format(
                        "\u00A7cNot worth it! %.1f%% above value (%s coins loss)",
                        Math.abs(profitPercent), formatNumber(netProfit)
                );
            } else {
                text = String.format(
                        "\u00A7eFair price (within %.0f%% of estimated value)",
                        NEUTRAL_BAND_PERCENT * 100.0
                );
            }
            lines.add(Component.literal(text));
        }

        if (MarketUtilsConfig.getMode() == PricingMode.DEBUG) {
            appendScanStats(lines);
            if (entry != null) {
                appendDebugBreakdown(lines, entry.price(), entry.sourceValues(), entry.autoSelectedSource());
            }
        }
    }

    /**
     * DEBUG mode: shows every source's raw value side by side, which one
     * AUTO would have picked, and the profit against that selection - for
     * comparing sources against each other, not for changing the border
     * grading (which still uses PriceProvider.resolveValue like every other
     * mode).
     */
    private static void appendDebugBreakdown(
            List<Component> lines,
            long price,
            Map<PriceSource, Long> values,
            PriceSource selected
    ) {
        lines.add(Component.literal("\u00A76--- MarketUtils Debug ---"));
        for (PriceSource source : PriceSource.values()) {
            long value = values.getOrDefault(source, 0L);
            String valueText = value > 0L ? formatAbsolute(value) : "N/A";
            lines.add(Component.literal("\u00A77" + source.displayName() + ": \u00A7f" + valueText));
        }

        if (selected == null) {
            lines.add(Component.literal("\u00A77Selected: \u00A7cNone"));
            return;
        }

        lines.add(Component.literal("\u00A77Selected: \u00A7e" + selected.displayName()));

        long selectedValue = values.get(selected);
        if (price > 0L && selectedValue > 0L) {
            long netProfit = NetProfitCalculator.netProfit(price, selectedValue);
            lines.add(Component.literal("\u00A77Estimated Profit (net): \u00A7f" + formatNumber(netProfit)));
            lines.add(Component.literal("\u00A77Would highlight: \u00A7f" + (shouldHighlight(netProfit) ? "Yes" : "No")));
        }
    }

    /** DEBUG mode: overall scan progress, independent of whether this specific item has been evaluated yet. */
    private static void appendScanStats(List<Component> lines) {
        int scanned = OBSERVED_SLOTS.size();
        // price/estimatedValue > 0 means data was actually found, regardless
        // of whether tintColor ended up 0 because it was filtered out.
        long priced = SLOT_CACHE.values().stream()
                .filter(e -> e.price() > 0L && e.estimatedValue() > 0L)
                .count();

        lines.add(Component.literal("\u00A76--- MarketUtils Scan ---"));
        lines.add(Component.literal("\u00A77Slots scanned: \u00A7f" + scanned + "/" + TOTAL_AH_SLOTS));
        lines.add(Component.literal("\u00A77Prices cached: \u00A7f" + priced + "/" + TOTAL_AH_SLOTS));

        if (scanComplete) {
            long scanMillis = (scanCompletedNanos - scanStartNanos) / 1_000_000L;
            long ageMillis = (System.nanoTime() - scanCompletedNanos) / 1_000_000L;
            lines.add(Component.literal("\u00A77Scan time: \u00A7f" + scanMillis + "ms"));
            lines.add(Component.literal("\u00A77Cache age: \u00A7f" + formatAge(ageMillis)));
        } else {
            lines.add(Component.literal("\u00A77Scan time: \u00A7escanning..."));
        }
    }

    /**
     * Called every frame from the render mixin with the screen instance.
     * A different instance than last time (reopening the AH, or any other
     * screen rebuild) means everything cached is for a different page.
     */
    public static void trackScreen(Object screenInstance) {
        if (currentScreenIdentity != screenInstance) {
            currentScreenIdentity = screenInstance;
            clearCache();
        }
    }

    /** Called from the render mixin when inventorySlot == hoveredSlot. */
    public static void setHoveredSlotIndex(int index) {
        hoveredSlotIndex = index;
    }

    /**
     * Called from the container-menu mixin every time the server pushes an
     * item into this menu, with Minecraft's own sync revision number. A
     * revision change for the SAME menu instance means the server just
     * refreshed this menu's contents (e.g. an AH page flip that reuses the
     * same open screen) - a reliable, cheap signal that doesn't depend on
     * item display names, which can be identical between two different
     * auctions of the same base item.
     */
    public static void onContainerRevisionSeen(Object menuInstance, int revision) {
        if (revisionTrackedMenu != menuInstance) {
            revisionTrackedMenu = menuInstance;
            lastSeenRevision = revision;
            return;
        }

        if (revision != lastSeenRevision) {
            lastSeenRevision = revision;
            clearCache();
        }
    }

    private static void checkScanComplete() {
        if (!scanComplete && OBSERVED_SLOTS.size() >= TOTAL_AH_SLOTS) {
            scanComplete = true;
            scanCompletedNanos = System.nanoTime();
        }
    }

    public static void clearCache() {
        SLOT_CACHE.clear();
        OBSERVED_SLOTS.clear();
        hoveredSlotIndex = -1;
        scanStartNanos = System.nanoTime();
        scanCompletedNanos = 0L;
        scanComplete = false;
    }

    // -- Internal evaluation --

    private static SlotProfitEntry evaluateFromTooltip(ItemStack stack, String fingerprint) {
        List<Component> tooltipLines = fetchFullTooltipLines(stack);
        if (tooltipLines.isEmpty()) {
            return new SlotProfitEntry(fingerprint, 0L, 0L, 0, Map.of(), null);
        }

        long price = 0L;

        for (Component line : tooltipLines) {
            String plain = PriceParser.stripFormatting(line.getString());
            String lower = plain.toLowerCase();
            int colon = plain.indexOf(':');
            if (colon == -1) {
                continue;
            }

            if (price == 0L && isListingPriceLabel(lower)) {
                long parsed = PriceParser.parsePrice(plain.substring(colon + 1));
                if (parsed > 0L) {
                    price = parsed;
                }
            }
        }

        // Computed unconditionally (not just in DEBUG mode) since it's cheap
        // and this is the one place it's safe to call getTooltipLines() -
        // appendTooltipText must never re-fetch the tooltip itself, since
        // it runs from inside the callback that's already building it.
        Map<PriceSource, Long> sourceValues = PriceProvider.findAllValues(tooltipLines);
        PriceSource autoSelectedSource = PriceProvider.findAutoSource(tooltipLines);

        // Estimated value comes from whichever source the current
        // PricingMode selects. See PriceProvider/PricingMode.
        long estimatedValue = PriceProvider.resolveValue(tooltipLines, MarketUtilsConfig.getMode());

        if (price <= 0L || estimatedValue <= 0L) {
            return new SlotProfitEntry(fingerprint, price, estimatedValue, 0, sourceValues, autoSelectedSource);
        }

        int color = computeTintColor(price, estimatedValue);
        return new SlotProfitEntry(fingerprint, price, estimatedValue, color, sourceValues, autoSelectedSource);
    }

    /**
     * Independently fetches this stack's full tooltip, including lines
     * added by every other mod (SkyHanni, COFL, etc.) regardless of
     * ItemTooltipCallback registration order. Returns an empty list if the
     * player/level aren't available yet.
     */
    private static List<Component> fetchFullTooltipLines(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return List.of();
        }

        return stack.getTooltipLines(
                Item.TooltipContext.of(mc.level),
                mc.player,
                TooltipFlag.Default.NORMAL
        );
    }

    // -- Label matching --

    /**
     * Matches tooltip lines that contain the auction listing price.
     *
     * IMPORTANT: Must exclude SkyHanni info lines that happen to contain
     * matching substrings. For example, "Lowest BIN Price:" contains
     * "bin price:" as a substring. "3 Day Avg. Price:" contains "price:".
     * These are NOT the listing price -- they are supplementary market data
     * added by SkyHanni. We must reject them before checking for matches.
     *
     * The real listing price ("Buy it now:", "Starting bid:", etc.) always
     * appears near the top of the tooltip, before any SkyHanni additions.
     * The parsing loop also uses first-match-wins to avoid overwriting.
     */
    private static boolean isListingPriceLabel(String lowerLine) {
        if (lowerLine.contains("lowest")
                || lowerLine.contains("avg")
                || lowerLine.contains("average")
                || lowerLine.contains("median")) {
            return false;
        }

        return lowerLine.contains("buy it now:")
                || lowerLine.contains("starting bid:")
                || lowerLine.contains("current bid:")
                || lowerLine.contains("top bid:")
                || lowerLine.contains("bin price:")
                || lowerLine.contains("buy-it-now:");
    }

    // -- Border rendering --

    /**
     * Draws a colored border inside the slot edges. Only the 4 edge strips
     * are drawn; the center area is untouched so SkyHanni's rarity
     * background and the item icon remain fully visible.
     */
    private static void renderBorder(GuiGraphicsExtractor g, Slot slot, int color) {
        if (color == 0) {
            return;
        }

        int x = slot.x;
        int y = slot.y;
        int t = BORDER_THICKNESS;

        g.fill(x, y, x + 16, y + t, color);
        g.fill(x, y + 16 - t, x + 16, y + 16, color);
        g.fill(x, y + t, x + t, y + 16 - t, color);
        g.fill(x + 16 - t, y + t, x + 16, y + 16 - t, color);
    }

    // -- Percentage-based color gradient --

    /**
     * Computes an ARGB color based on net profit (after the AH sell tax,
     * via NetProfitCalculator - never the raw price difference) expressed
     * as a percentage of estimated value.
     *
     * profitFraction = netProfit / estimatedValue
     *   positive => net profit after fees (good deal, green)
     *   negative => net loss after fees (bad deal, red)
     *   near zero => neutral (yellow)
     *
     * Returns 0 (no border) if there's no valid price data, or if the
     * profitable-only / minimum-profit-threshold settings filter this item
     * out - see shouldHighlight.
     */
    private static int computeTintColor(long price, long estimatedValue) {
        if (estimatedValue <= 0L || price <= 0L) {
            return 0;
        }

        long netProfit = NetProfitCalculator.netProfit(price, estimatedValue);

        if (!shouldHighlight(netProfit)) {
            return 0;
        }

        double profitFraction = (double) netProfit / (double) estimatedValue;

        if (Math.abs(profitFraction) < NEUTRAL_BAND_PERCENT) {
            return packColor(NEUTRAL_ALPHA, NEUTRAL_R, NEUTRAL_G, NEUTRAL_B);
        }

        // t = 0 right at the neutral edge, t = 1 at MAX_SCALE_PERCENT or
        // beyond. Interpolating FROM the neutral color (rather than
        // jumping straight to some other starting shade) means a flip
        // just barely past the neutral cutoff reads as "barely more than
        // neutral" instead of a random, possibly duller color that can
        // look like nothing rendered at all.
        double t = Math.min(1.0,
                (Math.abs(profitFraction) - NEUTRAL_BAND_PERCENT)
                / (MAX_SCALE_PERCENT - NEUTRAL_BAND_PERCENT));

        if (profitFraction > 0) {
            return lerpColor(
                    NEUTRAL_ALPHA, NEUTRAL_R, NEUTRAL_G, NEUTRAL_B,
                    DEEP_GREEN_ALPHA, DEEP_GREEN_R, DEEP_GREEN_G, DEEP_GREEN_B,
                    t
            );
        } else {
            return lerpColor(
                    NEUTRAL_ALPHA, NEUTRAL_R, NEUTRAL_G, NEUTRAL_B,
                    DEEP_RED_ALPHA, DEEP_RED_R, DEEP_RED_G, DEEP_RED_B,
                    t
            );
        }
    }

    private static int packColor(int alpha, int r, int g, int b) {
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpColor(int a0, int r0, int g0, int b0, int a1, int r1, int g1, int b1, double t) {
        int a = (int) (a0 + (a1 - a0) * t);
        int r = (int) (r0 + (r1 - r0) * t);
        int g = (int) (g0 + (g1 - g0) * t);
        int b = (int) (b0 + (b1 - b0) * t);
        return packColor(a, r, g, b);
    }

    /**
     * Whether an item with this net profit should get a border, per the
     * profitable-only and minimum-profit-threshold settings. Both apply
     * together - either one can suppress the border, matching how the
     * request described them as working together. Neither setting affects
     * the tooltip's worth-it text or DEBUG output, only border rendering.
     */
    private static boolean shouldHighlight(long netProfit) {
        if (MarketUtilsConfig.isProfitableOnly() && netProfit <= 0L) {
            return false;
        }

        return netProfit >= MarketUtilsConfig.getMinimumProfitThreshold();
    }

    // -- Frame throttling --

    private static void resetFrameCounterIfNewFrame() {
        long now = System.nanoTime();
        if (now - lastFrameStartNanos > 1_000_000L) {
            evaluationsThisFrame = 0;
            firstPassEvaluationsThisFrame = 0;
            lastFrameStartNanos = now;
        }
    }

    // -- Fingerprinting --

    private static String buildFingerprint(ItemStack stack) {
        return stack.getDisplayName().getString();
    }

    // -- Number formatting --

    private static String formatNumber(long number) {
        long absolute = Math.abs(number);
        String sign = number < 0 ? "-" : "+";
        if (absolute >= 1_000_000_000L) {
            return sign + String.format("%.2fB", absolute / 1_000_000_000.0);
        }
        if (absolute >= 1_000_000L) {
            return sign + String.format("%.2fM", absolute / 1_000_000.0);
        }
        if (absolute >= 1_000L) {
            return sign + String.format("%.1fK", absolute / 1_000.0);
        }
        return sign + absolute;
    }

    /** Same K/M/B formatting as formatNumber, without a forced +/- sign - for displaying a plain value rather than a delta. */
    private static String formatAbsolute(long number) {
        if (number >= 1_000_000_000L) {
            return String.format("%.2fB", number / 1_000_000_000.0);
        }
        if (number >= 1_000_000L) {
            return String.format("%.2fM", number / 1_000_000.0);
        }
        if (number >= 1_000L) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    private static String formatAge(long millis) {
        if (millis < 1000L) {
            return millis + "ms";
        }
        return String.format("%.1fs", millis / 1000.0);
    }
}
