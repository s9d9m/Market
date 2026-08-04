package com.marketutils.client.mixin;

import com.marketutils.client.render.ProfitRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void clearProfitCacheOnScreenOpen(CallbackInfo callbackInfo) {
        if (isAuctionScreen()) {
            ProfitRenderer.clearCache();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void clearProfitCacheOnScreenClose(CallbackInfo callbackInfo) {
        ProfitRenderer.clearCache();
    }

    /**
     * Inject at TAIL (after item icon and rarity backgrounds have been drawn)
     * so the profit border renders ON TOP of SkyHanni's rarity background
     * color and the item icon, but only at the thin 2px edges.
     */
    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void injectProfitOverlay(DrawContext drawContext, Slot inventorySlot, CallbackInfo callbackInfo) {
        if (inventorySlot == null) {
            return;
        }

        if (!isAuctionScreen()) {
            return;
        }

        boolean isPlayerSlot = inventorySlot.inventory instanceof PlayerInventory;
        if (isPlayerSlot) {
            return;
        }

        ProfitRenderer.renderSlotBackground(drawContext, inventorySlot);
    }

    private boolean isAuctionScreen() {
        Screen screenInstance = (Screen) (Object) this;
        Text titleComponent = screenInstance.getTitle();
        if (titleComponent == null) {
            return false;
        }

        String title = titleComponent.getString();
        return title.contains("Auction")
                || title.contains("Auctions")
                || title.contains("BIN");
    }
}
