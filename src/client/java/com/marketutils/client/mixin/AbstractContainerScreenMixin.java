package com.marketutils.client.mixin;

import com.marketutils.client.render.ProfitRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    // A mixin instance IS the screen instance for its whole lifetime, so the
    // title (and therefore whether this is an AH screen) never changes once
    // computed - caching it turns 54 getTitle()/getString()/contains() calls
    // per frame (one per slot, forever, for as long as the screen is open)
    // into exactly one, on the first slot of the first frame.
    @Unique
    private Boolean marketUtilsIsAuctionScreen;

    @Inject(method = "init", at = @At("TAIL"))
    private void clearProfitCacheOnScreenOpen(CallbackInfo callbackInfo) {
        if (isAuctionScreen()) {
            ProfitRenderer.clearCache();
        }
    }

    @Inject(method = "onClose", at = @At("HEAD"))
    private void clearProfitCacheOnScreenClose(CallbackInfo callbackInfo) {
        ProfitRenderer.clearCache();
    }

    /**
     * Inject at TAIL (after item icon and rarity backgrounds have been drawn)
     * so the profit border renders ON TOP of SkyHanni's rarity background
     * color and the item icon, but only at the thin 2px edges.
     *
     * Also doubles as the eager per-slot scan driver: this fires for every
     * visible slot every frame regardless of hover, so it's what performs
     * the "scan all 54 slots" pass ProfitRenderer.renderSlotBackground
     * already throttles internally. isAuctionScreen()'s identity is tracked
     * so a rebuilt screen instance (e.g. reopening the AH) resets the scan,
     * and inventorySlot == hoveredSlot is how the currently-hovered slot is
     * tracked for tooltip lookups, replacing the old display-name matching.
     */
    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void injectProfitOverlay(GuiGraphicsExtractor guiGraphics, Slot inventorySlot, int i, int j, CallbackInfo callbackInfo) {
        if (inventorySlot == null) {
            return;
        }

        if (!isAuctionScreen()) {
            return;
        }

        ProfitRenderer.trackScreen(this);

        if (inventorySlot == hoveredSlot) {
            ProfitRenderer.setHoveredSlotIndex(inventorySlot.index);
        }

        boolean isPlayerSlot = inventorySlot.container instanceof Inventory;
        if (isPlayerSlot) {
            return;
        }

        ProfitRenderer.renderSlotBackground(guiGraphics, inventorySlot);
    }

    private boolean isAuctionScreen() {
        if (marketUtilsIsAuctionScreen == null) {
            Screen screenInstance = (Screen) (Object) this;
            Component titleComponent = screenInstance.getTitle();
            String title = titleComponent == null ? "" : titleComponent.getString();
            marketUtilsIsAuctionScreen = title.contains("Auction")
                    || title.contains("Auctions")
                    || title.contains("BIN");
        }
        return marketUtilsIsAuctionScreen;
    }
}
