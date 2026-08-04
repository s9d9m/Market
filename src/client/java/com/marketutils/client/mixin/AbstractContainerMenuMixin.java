package com.marketutils.client.mixin;

import com.marketutils.client.render.ProfitRenderer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects when the server pushes updated container contents - e.g. an
 * Auction House page flip, which typically reuses the same open screen
 * and just swaps items via this same synced-content mechanism rather than
 * closing/reopening the screen.
 *
 * setItem's "revision" parameter is Minecraft's own container-sync
 * counter, confirmed real from SkyBlocker's AuctionHouseScreenHandler,
 * which overrides this exact method with this exact signature. A change
 * here is a definitive, cheap signal that this menu's items were just
 * refreshed by the server - unlike comparing item display names, which
 * can't tell two different auctions of the same base item apart.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "setItem", at = @At("HEAD"))
    private void onSetItem(int slot, int revision, ItemStack stack, CallbackInfo callbackInfo) {
        ProfitRenderer.onContainerRevisionSeen(this, revision);
    }
}
