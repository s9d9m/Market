package com.marketutils.client.render;

import com.marketutils.client.util.MarketUtilsConfig;
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
 * this scan cycle is uncapped, so a freshly opened/changed page populates
 * in a single pass instead of trickling in - at the cost of a brief frame
 * stutter right on page open, favoring speed for flipping over smoothness.
 * Only RETRIES of a slot that came back empty (waiting on an async source
 * like COFL) are throttled to MAX_EVALUATIONS_PER_FRAME, so items nothing
 * ever prices don't get re-evaluated every frame forever. Hovering an item
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
    private static int evaluationsThisFrame = 0;
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
    private static final double MAX_SCALE_PERCENT = 0.50;

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

        // Only trust a cached "no value" result if it actually found one.
        // A source like COFL's median can take a moment to become available
        // after login/screen-open (e.g. an async fetch not finished yet), so
        // a slot that found nothing on its first evaluation must keep being
        // retried on later frames rather than being stuck silent forever.
        if (cached != null && cached.tintColor() != 0) {
            renderBorder(guiGraphics, slot, cached.tintColor());
            return;
        }

        // A slot's first-ever evaluation this scan cycle runs immediately,
        // uncapped, so a freshly opened/changed page populates in one pass
        // instead of trickling in a few slots per frame. Only RETRIES of a
        // slot that already came back empty are throttled - otherwise an
        // item no price source tracks would get re-evaluated every single
        // frame forever, which is a worse, permanent cost instead of one
        // brief stutter on page open.
        boolean isFirstAttempt = cached == null;
        if (!isFirstAttempt && evaluationsThisFrame >= MAX_EVALUATIONS_PER_FRAME) {
            return;
        }

        evaluating = true;
        try {
            SlotProfitEntry entry = evaluateFromTooltip(stack, fingerprint);
            SLOT_CACHE.put(slotIndex, entry);
            if (!isFirstAttempt) {
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
            long delta = entry.estimatedValue() - entry.price();
            double profitPercent = (double) delta / (double) entry.estimatedValue() * 100.0;

            String text;
            if (profitPercent > NEUTRAL_BAND_PERCENT * 100.0) {
                text = String.format(
                        "\u00A7aWorth it! %.1f%% below value (%s coins profit)",
                        profitPercent, formatNumber(delta)
                );
            } else if (profitPercent < -(NEUTRAL_BAND_PERCENT * 100.0)) {
                text = String.format(
                        "\u00A7cNot worth it! %.1f%% above value (%s coins loss)",
                        Math.abs(profitPercent), formatNumber(delta)
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
            long delta = selectedValue - price;
            lines.add(Component.literal("\u00A77Estimated Profit: \u00A7f" + formatNumber(delta)));
        }
    }

    /** DEBUG mode: overall scan progress, independent of whether this specific item has been evaluated yet. */
    private static void appendScanStats(List<Component> lines) {
        int scanned = OBSERVED_SLOTS.size();
        long priced = SLOT_CACHE.values().stream().filter(e -> e.tintColor() != 0).count();

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
     * Computes an ARGB color based on how far the listing price is from the
     * estimated value, expressed as a percentage of estimated value.
     *
     * profitFraction = (estimatedValue - price) / estimatedValue
     *   positive => BIN is below estimated (good deal, green)
     *   negative => BIN is above estimated (bad deal, red)
     *   near zero => neutral (yellow)
     */
    private static int computeTintColor(long price, long estimatedValue) {
        if (estimatedValue <= 0L || price <= 0L) {
            return 0;
        }

        double profitFraction = (double) (estimatedValue - price) / (double) estimatedValue;

        if (Math.abs(profitFraction) < NEUTRAL_BAND_PERCENT) {
            return (130 << 24) | (0xE0 << 16) | (0xD0 << 8) | 0x00;
        }

        if (profitFraction > 0) {
            // Good deal: BIN is below estimated value -> green
            double t = Math.min(1.0,
                    (profitFraction - NEUTRAL_BAND_PERCENT)
                    / (MAX_SCALE_PERCENT - NEUTRAL_BAND_PERCENT));

            int r = (int) (210 * (1.0 - t));
            int g = (int) (170 + 50 * t);
            int b = 0;
            int alpha = (int) (140 + 70 * t);
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        } else {
            // Bad deal: BIN is above estimated value -> red
            double t = Math.min(1.0,
                    (Math.abs(profitFraction) - NEUTRAL_BAND_PERCENT)
                    / (MAX_SCALE_PERCENT - NEUTRAL_BAND_PERCENT));

            int r = (int) (200 + 40 * t);
            int g = (int) (170 * (1.0 - t));
            int b = 0;
            int alpha = (int) (140 + 70 * t);
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        }
    }

    // -- Frame throttling --

    private static void resetFrameCounterIfNewFrame() {
        long now = System.nanoTime();
        if (now - lastFrameStartNanos > 1_000_000L) {
            evaluationsThisFrame = 0;
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
