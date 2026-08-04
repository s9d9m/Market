package com.marketutils.client;

import com.marketutils.client.render.ProfitRenderer;
import com.marketutils.client.util.MarketUtilsConfig;
import com.marketutils.client.util.PricingMode;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

public class MarketutilsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ItemTooltipCallback.EVENT.register((itemStack, tooltipContext, tooltipFlag, lines) -> {
			ProfitRenderer.appendTooltipText(itemStack, lines);
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(ClientCommands.literal("marketutils")
						.then(ClientCommands.literal("mode")
								.executes(context -> {
									context.getSource().sendFeedback(Component.literal(
											"[MarketUtils] Current pricing mode: " + MarketUtilsConfig.getMode()));
									return Command.SINGLE_SUCCESS;
								})
								.then(ClientCommands.argument("value", StringArgumentType.word())
										.executes(context -> {
											String requested = StringArgumentType.getString(context, "value");
											try {
												PricingMode mode = PricingMode.valueOf(requested.toUpperCase());
												MarketUtilsConfig.setMode(mode);
												ProfitRenderer.clearCache();
												context.getSource().sendFeedback(Component.literal(
														"[MarketUtils] Pricing mode set to " + mode));
											} catch (IllegalArgumentException e) {
												context.getSource().sendError(Component.literal(
														"[MarketUtils] Unknown mode. Valid modes: AUTO, COFL_MEDIAN, SKYHANNI, CRAFT_PRICE, DEBUG"));
											}
											return Command.SINGLE_SUCCESS;
										})))));
	}
}
