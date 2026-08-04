package com.marketutils.client.render;

import com.marketutils.client.util.PriceParser;
import com.marketutils.client.util.PriceProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates whether auction items are worth buying by comparing listing price
 * to an estimated value sourced via PriceProvider (COFL median, SkyBlocker,
 * SkyHanni, or craft price, in that priority order). Renders a colored
 * BORDER around slots (so SkyHanni's rarity backgrounds remain visible) and
 * appends debug text to tooltips.
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

    private static final int BORDER_THICKNESS = 2;

    private static final double NEUTRAL_BAND_PERCENT = 0.03;
    private static final double MAX_SCALE_PERCENT = 0.50;

    private record SlotProfitEntry(
            String fingerprint,
            long price,
            long estimatedValue,
            int tintColor
    ) {}

    private ProfitRenderer() {}

    /**
     * Called from the renderSlot mixin (at TAIL, after item icon and rarity
     * backgrounds have been drawn). Draws a 2px colored border on top of
     * everything at the slot edges, leaving the center visible for rarity
     * backgrounds from SkyHanni and the item icon.
     */
    public static void renderSlotBackground(GuiGraphicsExtractor guiGraphics, Slot slot) {
        if (slot == null) {
            return;
        }

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

        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            renderBorder(guiGraphics, slot, cached.tintColor());
            return;
        }

        if (evaluationsThisFrame >= MAX_EVALUATIONS_PER_FRAME) {
            return;
        }

        evaluating = true;
        try {
            SlotProfitEntry entry = evaluateFromTooltip(stack, fingerprint);
            SLOT_CACHE.put(slotIndex, entry);
            evaluationsThisFrame++;
            renderBorder(guiGraphics, slot, entry.tintColor());
        } finally {
            evaluating = false;
        }
    }

    /**
     * Called from the ItemTooltipCallback registered in MarketutilsClient.
     *
     * This callback parses the tooltip lines that have been assembled SO FAR.
     * SkyHanni's "Estimated Item Value" line may or may not be present
     * depending on callback registration order.
     *
     * To handle this, the tooltip text falls back to the SLOT_CACHE (which
     * was populated by renderSlotBackground using a separate getTooltipLines
     * call that includes all mods' lines). If the cache has data for this
     * item, the tooltip text is derived from the cache. If not (e.g., the
     * item hasn't been rendered yet), we try parsing the provided lines.
     */
    public static void appendTooltipText(ItemStack stack, List<Component> lines) {
        if (stack == null || stack.isEmpty() || lines == null) {
            return;
        }

        if (evaluating) {
            return;
        }


        long price = 0L;
        long estimatedValue = 0L;

        // First: check the slot cache for pre-computed data from renderSlotBackground.
        // The slot cache was populated via getTooltipLines() which fires ALL mod
        // callbacks (including SkyHanni), so it reliably has the estimated value.
        SlotProfitEntry cachedEntry = findCachedEntryForStack(stack);
        if (cachedEntry != null && cachedEntry.price() > 0L && cachedEntry.estimatedValue() > 0L) {
            price = cachedEntry.price();
            estimatedValue = cachedEntry.estimatedValue();
        } else {
            // Fallback: try parsing the provided tooltip lines directly.
            // This works if SkyHanni's callback fired before ours.
            for (Component line : lines) {
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

            // Estimated value comes from whichever compatible mod's line is
            // present, in priority order: COFL median > SkyBlocker > SkyHanni
            // > craft price. See PriceProvider for the priority chain.
            estimatedValue = PriceProvider.findEstimatedValue(lines);
        }

        if (price > 0L && estimatedValue > 0L) {
            long delta = estimatedValue - price;
            double profitPercent = (double) delta / (double) estimatedValue * 100.0;

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
    }

    public static void clearCache() {
        SLOT_CACHE.clear();
    }

    // -- Cache lookup for tooltip fallback --

    /**
     * Scans the slot cache for an entry whose fingerprint matches this
     * stack's display name. Used by appendTooltipText to retrieve the
     * pre-computed price/value from the render path.
     */
    private static SlotProfitEntry findCachedEntryForStack(ItemStack stack) {
        String targetFingerprint = buildFingerprint(stack);
        for (SlotProfitEntry entry : SLOT_CACHE.values()) {
            if (entry.fingerprint().equals(targetFingerprint)) {
                return entry;
            }
        }
        return null;
    }

    // -- Internal evaluation --

    private static SlotProfitEntry evaluateFromTooltip(ItemStack stack, String fingerprint) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return new SlotProfitEntry(fingerprint, 0L, 0L, 0);
        }

        List<Component> tooltipLines = stack.getTooltipLines(
                Item.TooltipContext.of(mc.level),
                mc.player,
                TooltipFlag.Default.NORMAL
        );

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

        // Estimated value comes from whichever compatible mod's line is
        // present, in priority order: COFL median > SkyBlocker > SkyHanni
        // > craft price. See PriceProvider for the priority chain.
        long estimatedValue = PriceProvider.findEstimatedValue(tooltipLines);

        if (price <= 0L || estimatedValue <= 0L) {
            return new SlotProfitEntry(fingerprint, price, estimatedValue, 0);
        }

        int color = computeTintColor(price, estimatedValue);
        return new SlotProfitEntry(fingerprint, price, estimatedValue, color);
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
}
