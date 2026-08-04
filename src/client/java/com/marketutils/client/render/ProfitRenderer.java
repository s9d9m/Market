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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Evaluates whether auction items are worth buying by comparing listing price
 * to an estimated value sourced via PriceProvider, according to whichever
 * PricingMode is currently selected in MarketUtilsConfig (see
 * "/marketutils mode"). Renders a colored BORDER around slots (so SkyHanni's
 * rarity backgrounds remain visible) and appends debug text to tooltips.
 *
 * Caching strategy: every visible slot gets evaluated and cached by slot
 * index (a plain array, not a Map, since a 54-slot AH page is a fixed,
 * known size) as soon as the AH screen renders it. There is no per-frame
 * evaluation *count* cap anymore - instead, each frame gets a nanosecond
 * TIME budget (FRAME_EVAL_BUDGET_NANOS) to spend on evaluations, so on
 * hardware/modsets where getTooltipLines() is cheap the whole 54-slot page
 * finishes in a single frame, while on slower ones it naturally spreads
 * across as many frames as it actually needs - the cap adapts to reality
 * instead of guessing a fixed item count. Slots already resolved by a given
 * frame are a near-free array read, so unspent budget effectively "rolls
 * forward" onto whatever's left as the scan progresses across frames.
 *
 * Items that keep coming back with no price (nothing tracks them, or a
 * source hasn't loaded yet) retry every frame for the first
 * QUICK_RETRY_WINDOW_NANOS, then back off to one retry every
 * RETRY_BACKOFF_NANOS - so a permanently-unpriceable item doesn't burn a
 * getTooltipLines() call 60+ times a second forever for as long as the page
 * stays open, while a source that loads a little late still eventually gets
 * picked up.
 *
 * Hovering an item only ever reads the cache - it never re-evaluates or
 * re-parses tooltip text on its own. The currently-hovered slot is tracked
 * directly from Minecraft's own hoveredSlot field (via the mixin), not by
 * matching item display names, so results can never be shown for the wrong
 * slot.
 *
 * Color scale is percentage-based:
 *   BIN far below estimated value  -> deep green border  (great deal)
 *   BIN slightly below             -> yellow-green border
 *   BIN roughly equal              -> yellow border      (neutral)
 *   BIN slightly above             -> orange border
 *   BIN far above estimated value  -> deep red border    (bad deal)
 */
public final class ProfitRenderer {

    private static final int TOTAL_AH_SLOTS = 54;

    // Per-frame evaluation time budget, replacing the old fixed per-frame
    // evaluation *count* caps (3 retries / 12 first-attempts). 8ms is half
    // of a 60fps frame (16.67ms), leaving headroom for actual rendering and
    // every other mod's own per-frame work - but it's a reasoned default,
    // not a measured one (this sandbox can't run real Minecraft to profile
    // real hardware). DEBUG mode's scan stats report actual time-to-full-
    // scan and per-phase timings so this can be tuned against real numbers
    // instead of guessed a second time.
    private static final long FRAME_EVAL_BUDGET_NANOS = 8_000_000L;

    // A slot's item is retried every single frame for its first second of
    // being unresolved (catches most async price sources loading shortly
    // after page open), then backed off to at most once every 2 seconds -
    // otherwise a genuinely unpriceable item (no source ever tracks it)
    // would keep costing a full getTooltipLines() call every frame forever,
    // for as long as the AH page stays open.
    private static final long QUICK_RETRY_WINDOW_NANOS = 1_000_000_000L;
    private static final long RETRY_BACKOFF_NANOS = 2_000_000_000L;

    private static long evalNanosThisFrame = 0L;
    private static long lastFrameTickNanos = 0L;

    private static boolean evaluating = false;

    // Fixed-size arrays instead of HashMap<Integer,_>/HashSet<Integer> -
    // every visible slot is read from this every single frame regardless of
    // hover, forever, for as long as an AH screen is open. A 54-slot AH page
    // is a known fixed size, so a plain array removes Integer boxing and
    // hashing from that hot path entirely; it's a direct index, not a hash
    // lookup.
    private static final SlotProfitEntry[] SLOT_CACHE = new SlotProfitEntry[TOTAL_AH_SLOTS];
    private static final boolean[] SLOT_OBSERVED = new boolean[TOTAL_AH_SLOTS];
    private static int observedSlotCount = 0;

    private static volatile int hoveredSlotIndex = -1;
    private static Object currentScreenIdentity;

    private static Object revisionTrackedMenu;
    private static int lastSeenRevision = Integer.MIN_VALUE;

    private static volatile long scanStartNanos = System.nanoTime();
    private static volatile long scanCompletedNanos = 0L;
    private static volatile boolean scanComplete = false;

    // Measurement only (DEBUG mode's scan stats) - none of this feeds back
    // into any logic decision. Split into the same phases the performance
    // review asked about, so real per-phase timings are visible in-game
    // instead of guessed: tooltip generation (getTooltipLines() itself),
    // price extraction/parsing, profit calculation (source selection +
    // color), border rendering, and cache lookup.
    private static volatile int totalTooltipParses = 0;
    private static volatile int cacheHitsThisScan = 0;
    private static volatile int cacheMissesThisScan = 0;
    private static volatile long totalTooltipGenNanos = 0L;
    private static volatile long totalParseNanos = 0L;
    private static volatile long totalProfitCalcNanos = 0L;
    private static volatile long totalBorderRenderNanos = 0L;
    private static volatile long totalCacheLookupNanos = 0L;

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

    /** Pure pricing output for one evaluation - no cache-scheduling metadata. */
    private record EvaluationResult(
            long price,
            long estimatedValue,
            int tintColor,
            Map<PriceSource, Long> sourceValues,
            PriceSource autoSelectedSource
    ) {}

    /**
     * A slot's cached state, including retry-scheduling metadata.
     * firstAttemptNanos/lastAttemptNanos drive the quick-retry-then-backoff
     * schedule - see the class javadoc and renderSlotBackground.
     */
    private record SlotProfitEntry(
            long price,
            long estimatedValue,
            int tintColor,
            Map<PriceSource, Long> sourceValues,
            PriceSource autoSelectedSource,
            long firstAttemptNanos,
            long lastAttemptNanos
    ) {
        boolean isPriced() {
            return price > 0L && estimatedValue > 0L;
        }
    }

    private ProfitRenderer() {}

    /**
     * Called from the render mixin for every visible slot, every frame,
     * regardless of hover. This is what drives the eager full-page scan:
     * each new (or invalidated) slot gets evaluated once, throttled by a
     * per-frame time budget rather than a fixed item count, and cached by
     * slot index. Draws a 2px colored border on top of everything at the
     * slot edges, leaving the center visible for rarity backgrounds from
     * SkyHanni and the item icon.
     */
    public static void renderSlotBackground(GuiGraphicsExtractor guiGraphics, Slot slot) {
        if (slot == null) {
            return;
        }

        int slotIndex = slot.index;
        if (slotIndex < 0 || slotIndex >= TOTAL_AH_SLOTS) {
            // Some other "Auction"/"Auctions"/"BIN"-titled screen with a
            // different slot layout than the standard 54-slot AH page (e.g.
            // the create-listing screen) - skip it rather than risk an
            // out-of-bounds cache access.
            return;
        }

        if (!SLOT_OBSERVED[slotIndex]) {
            SLOT_OBSERVED[slotIndex] = true;
            observedSlotCount++;
        }
        checkScanComplete();

        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        long lookupStart = System.nanoTime();
        SlotProfitEntry cached = SLOT_CACHE[slotIndex];
        boolean stableHit = cached != null && cached.isPriced();
        totalCacheLookupNanos += System.nanoTime() - lookupStart;

        // Fast path: a stable cached result is trusted with no rebuild work
        // at all - not even a display-name read. Page-level invalidation is
        // handled entirely by onContainerRevisionSeen (Minecraft's own
        // server-authoritative sync revision - see AbstractContainerMenuMixin)
        // and trackScreen (screen-instance identity), both of which clear
        // the whole cache the moment either signal fires, independently of
        // this per-slot per-frame path.
        //
        // Only trust a cached result if price data was actually found - NOT
        // just tintColor != 0, since a fully-resolved item can legitimately
        // have tintColor == 0 now (filtered out by the profitable-only or
        // minimum-profit settings). Checking price/estimatedValue directly
        // (via isPriced()) keeps that case correctly treated as stable/
        // cached, while a slot that genuinely found nothing yet (e.g.
        // COFL's median not loaded yet) still keeps retrying on later
        // frames per the quick-retry/backoff schedule below.
        if (stableHit) {
            cacheHitsThisScan++;
            renderBorderTimed(guiGraphics, slot, cached.tintColor());
            return;
        }

        if (evaluating) {
            return;
        }

        resetFrameBudgetIfNewFrame();

        long now = System.nanoTime();
        if (cached != null) {
            boolean pastQuickRetryWindow = now - cached.firstAttemptNanos() > QUICK_RETRY_WINDOW_NANOS;
            boolean dueForRetry = !pastQuickRetryWindow || now - cached.lastAttemptNanos() > RETRY_BACKOFF_NANOS;
            if (!dueForRetry) {
                return;
            }
        }

        if (evalNanosThisFrame >= FRAME_EVAL_BUDGET_NANOS) {
            // Out of this frame's evaluation budget - resume next frame.
            // Slots already resolved by then are a near-free array lookup,
            // so the budget effectively rolls forward onto whatever's left
            // instead of restarting from slot 0 every frame.
            return;
        }

        cacheMissesThisScan++;
        long evalStart = now;
        evaluating = true;
        SlotProfitEntry entry;
        try {
            EvaluationResult result = evaluateFromTooltip(stack);
            long firstAttemptNanos = cached != null ? cached.firstAttemptNanos() : evalStart;
            entry = new SlotProfitEntry(
                    result.price(), result.estimatedValue(), result.tintColor(),
                    result.sourceValues(), result.autoSelectedSource(),
                    firstAttemptNanos, evalStart
            );
            SLOT_CACHE[slotIndex] = entry;
        } finally {
            evaluating = false;
        }
        evalNanosThisFrame += System.nanoTime() - evalStart;

        renderBorderTimed(guiGraphics, slot, entry.tintColor());
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

        SlotProfitEntry entry = (hoveredSlotIndex >= 0 && hoveredSlotIndex < TOTAL_AH_SLOTS)
                ? SLOT_CACHE[hoveredSlotIndex]
                : null;

        if (entry != null && entry.isPriced()) {
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
        for (PriceSource source : PriceSource.VALUES) {
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

    /** DEBUG mode: overall scan progress and real per-phase timings, independent of whether this specific item has been evaluated yet. */
    private static void appendScanStats(List<Component> lines) {
        int scanned = observedSlotCount;
        int priced = 0;
        for (SlotProfitEntry e : SLOT_CACHE) {
            if (e != null && e.isPriced()) {
                priced++;
            }
        }

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

        lines.add(Component.literal("\u00A77Tooltip parses: \u00A7f" + totalTooltipParses));
        lines.add(Component.literal("\u00A77Cache hits/misses: \u00A7f" + cacheHitsThisScan + "/" + cacheMissesThisScan));

        int evals = Math.max(totalTooltipParses, 1);
        int lookups = Math.max(cacheHitsThisScan + cacheMissesThisScan, 1);
        lines.add(Component.literal("\u00A76--- Phase timings (this page) ---"));
        lines.add(Component.literal("\u00A77Tooltip gen: \u00A7f" + formatMillis(totalTooltipGenNanos)
                + " total, " + formatMicros(totalTooltipGenNanos / evals) + " avg"));
        lines.add(Component.literal("\u00A77Price extraction: \u00A7f" + formatMillis(totalParseNanos)
                + " total, " + formatMicros(totalParseNanos / evals) + " avg"));
        lines.add(Component.literal("\u00A77Profit calc: \u00A7f" + formatMillis(totalProfitCalcNanos)
                + " total, " + formatMicros(totalProfitCalcNanos / evals) + " avg"));
        lines.add(Component.literal("\u00A77Border render: \u00A7f" + formatMillis(totalBorderRenderNanos)
                + " total, " + formatMicros(totalBorderRenderNanos / lookups) + " avg"));
        lines.add(Component.literal("\u00A77Cache lookup: \u00A7f" + formatMillis(totalCacheLookupNanos)
                + " total, " + formatMicros(totalCacheLookupNanos / lookups) + " avg"));
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
        if (!scanComplete && observedSlotCount >= TOTAL_AH_SLOTS) {
            scanComplete = true;
            scanCompletedNanos = System.nanoTime();
        }
    }

    public static void clearCache() {
        Arrays.fill(SLOT_CACHE, null);
        Arrays.fill(SLOT_OBSERVED, false);
        observedSlotCount = 0;
        hoveredSlotIndex = -1;
        scanStartNanos = System.nanoTime();
        scanCompletedNanos = 0L;
        scanComplete = false;
        totalTooltipParses = 0;
        cacheHitsThisScan = 0;
        cacheMissesThisScan = 0;
        totalTooltipGenNanos = 0L;
        totalParseNanos = 0L;
        totalProfitCalcNanos = 0L;
        totalBorderRenderNanos = 0L;
        totalCacheLookupNanos = 0L;
    }

    // -- Internal evaluation --

    private static EvaluationResult evaluateFromTooltip(ItemStack stack) {
        long t0 = System.nanoTime();
        List<Component> tooltipLines = fetchFullTooltipLines(stack);
        long t1 = System.nanoTime();
        totalTooltipGenNanos += (t1 - t0);
        totalTooltipParses++;

        if (tooltipLines.isEmpty()) {
            return new EvaluationResult(0L, 0L, 0, Map.of(), null);
        }

        long price = 0L;
        Map<PriceSource, Long> sourceValues = PriceProvider.newValueMap();

        // Single pass over the tooltip: formatting/lowercasing/colon-lookup
        // happens exactly once per line, and that one line is checked for
        // BOTH the listing price and every known PriceSource's label in the
        // same iteration - previously this was up to ~11 separate full
        // scans of the same small list per item (one for price, one per
        // source in findAllValues, one per source walking AUTO_PRIORITY
        // twice more for findAutoSource/resolveValue).
        for (Component line : tooltipLines) {
            String plain = PriceParser.stripFormatting(line.getString());
            String lower = plain.toLowerCase();
            int colon = plain.indexOf(':');
            if (colon == -1) {
                continue;
            }
            String afterColon = plain.substring(colon + 1);

            if (price == 0L && isListingPriceLabel(lower)) {
                long parsed = PriceParser.parsePrice(afterColon);
                if (parsed > 0L) {
                    price = parsed;
                }
            }

            PriceProvider.accumulateSourceValues(lower, afterColon, sourceValues);
        }
        long t2 = System.nanoTime();
        totalParseNanos += (t2 - t1);

        // Computed unconditionally (not just in DEBUG mode) since it's cheap
        // and this is the one place it's safe to call getTooltipLines() -
        // appendTooltipText must never re-fetch the tooltip itself, since
        // it runs from inside the callback that's already building it.
        PriceSource autoSelectedSource = PriceProvider.findAutoSource(sourceValues);

        // Estimated value comes from whichever source the current
        // PricingMode selects. See PriceProvider/PricingMode.
        long estimatedValue = PriceProvider.resolveValue(sourceValues, MarketUtilsConfig.getMode());

        if (price <= 0L || estimatedValue <= 0L) {
            totalProfitCalcNanos += (System.nanoTime() - t2);
            return new EvaluationResult(price, estimatedValue, 0, sourceValues, autoSelectedSource);
        }

        int color = computeTintColor(price, estimatedValue);
        totalProfitCalcNanos += (System.nanoTime() - t2);
        return new EvaluationResult(price, estimatedValue, color, sourceValues, autoSelectedSource);
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

    private static void renderBorderTimed(GuiGraphicsExtractor g, Slot slot, int color) {
        long start = System.nanoTime();
        renderBorder(g, slot, color);
        totalBorderRenderNanos += System.nanoTime() - start;
    }

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

    private static void resetFrameBudgetIfNewFrame() {
        long now = System.nanoTime();
        if (now - lastFrameTickNanos > 1_000_000L) {
            evalNanosThisFrame = 0L;
            lastFrameTickNanos = now;
        }
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

    private static String formatMillis(long nanos) {
        return String.format("%.2fms", nanos / 1_000_000.0);
    }

    private static String formatMicros(long nanos) {
        return String.format("%.1fus", nanos / 1_000.0);
    }
}
