package com.marketutils.client.mixin;

import com.marketutils.client.render.ProfitRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

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
     */
    @Inject(method = "renderSlot", at = @At("TAIL"))
    private void injectProfitOverlay(GuiGraphics guiGraphics, Slot inventorySlot, CallbackInfo callbackInfo) {
        if (inventorySlot == null) {
            return;
        }

        if (!isAuctionScreen()) {
            return;
        }

        boolean isPlayerSlot = inventorySlot.container instanceof Inventory;
        if (isPlayerSlot) {
            return;
        }

        ProfitRenderer.renderSlotBackground(guiGraphics, inventorySlot);
    }

    private boolean isAuctionScreen() {
        Screen screenInstance = (Screen) (Object) this;
        Component titleComponent = screenInstance.getTitle();
        if (titleComponent == null) {
            return false;
        }

        String title = titleComponent.getString();
        return title.contains("Auction")
                || title.contains("Auctions")
                || title.contains("BIN");
    }
}
