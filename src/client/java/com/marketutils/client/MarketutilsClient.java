package com.marketutils.client;

import com.marketutils.client.render.ProfitRenderer;
import com.marketutils.client.util.FilterMode;
import com.marketutils.client.util.MarketUtilsConfig;
import com.marketutils.client.util.PriceParser;
import com.marketutils.client.util.PricingMode;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
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

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			var modeCommand = ClientCommands.literal("mode")
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
							}));

			var profitableOnlyCommand = ClientCommands.literal("profitableonly")
					.executes(context -> {
						context.getSource().sendFeedback(Component.literal(
								"[MarketUtils] Profitable-only highlighting: "
										+ (MarketUtilsConfig.isProfitableOnly() ? "ON" : "OFF")));
						return Command.SINGLE_SUCCESS;
					})
					.then(ClientCommands.argument("enabled", BoolArgumentType.bool())
							.executes(context -> {
								boolean enabled = BoolArgumentType.getBool(context, "enabled");
								MarketUtilsConfig.setProfitableOnly(enabled);
								ProfitRenderer.clearCache();
								context.getSource().sendFeedback(Component.literal(
										"[MarketUtils] Profitable-only highlighting: " + (enabled ? "ON" : "OFF")));
								return Command.SINGLE_SUCCESS;
							}));

			var minProfitCommand = ClientCommands.literal("minprofit")
					.executes(context -> {
						long threshold = MarketUtilsConfig.getMinimumProfitThreshold();
						String display = threshold == MarketUtilsConfig.NO_MINIMUM_PROFIT
								? "disabled"
								: String.valueOf(threshold);
						context.getSource().sendFeedback(Component.literal(
								"[MarketUtils] Minimum profit threshold: " + display));
						return Command.SINGLE_SUCCESS;
					})
					.then(ClientCommands.argument("value", StringArgumentType.word())
							.executes(context -> {
								String requested = StringArgumentType.getString(context, "value");

								if (requested.equalsIgnoreCase("off") || requested.equalsIgnoreCase("none")) {
									MarketUtilsConfig.setMinimumProfitThreshold(MarketUtilsConfig.NO_MINIMUM_PROFIT);
									ProfitRenderer.clearCache();
									context.getSource().sendFeedback(Component.literal(
											"[MarketUtils] Minimum profit threshold disabled"));
									return Command.SINGLE_SUCCESS;
								}

								long parsed = PriceParser.parsePrice(requested);
								if (parsed <= 0L) {
									context.getSource().sendError(Component.literal(
											"[MarketUtils] Couldn't parse a profit value from '" + requested
													+ "'. Try something like 5m, 10m, 25m, 50m, or 'off'."));
									return Command.SINGLE_SUCCESS;
								}

								MarketUtilsConfig.setMinimumProfitThreshold(parsed);
								ProfitRenderer.clearCache();
								context.getSource().sendFeedback(Component.literal(
										"[MarketUtils] Minimum profit threshold set to " + parsed));
								return Command.SINGLE_SUCCESS;
							}));

			var minRoiCommand = ClientCommands.literal("minroi")
					.executes(context -> {
						double threshold = MarketUtilsConfig.getMinimumRoiPercent();
						String display = threshold == MarketUtilsConfig.NO_MINIMUM_ROI
								? "disabled"
								: threshold + "%";
						context.getSource().sendFeedback(Component.literal(
								"[MarketUtils] Minimum ROI threshold: " + display));
						return Command.SINGLE_SUCCESS;
					})
					.then(ClientCommands.argument("value", StringArgumentType.word())
							.executes(context -> {
								String requested = StringArgumentType.getString(context, "value");

								if (requested.equalsIgnoreCase("off") || requested.equalsIgnoreCase("none")) {
									MarketUtilsConfig.setMinimumRoiPercent(MarketUtilsConfig.NO_MINIMUM_ROI);
									ProfitRenderer.clearCache();
									context.getSource().sendFeedback(Component.literal(
											"[MarketUtils] Minimum ROI threshold disabled"));
									return Command.SINGLE_SUCCESS;
								}

								String numeric = requested.endsWith("%")
										? requested.substring(0, requested.length() - 1)
										: requested;

								double parsed;
								try {
									parsed = Double.parseDouble(numeric);
								} catch (NumberFormatException e) {
									context.getSource().sendError(Component.literal(
											"[MarketUtils] Couldn't parse an ROI value from '" + requested
													+ "'. Try something like 2, 5, 8, 10, 15, or 'off'."));
									return Command.SINGLE_SUCCESS;
								}

								MarketUtilsConfig.setMinimumRoiPercent(parsed);
								ProfitRenderer.clearCache();
								context.getSource().sendFeedback(Component.literal(
										"[MarketUtils] Minimum ROI threshold set to " + parsed + "%"));
								return Command.SINGLE_SUCCESS;
							}));

			var filterModeCommand = ClientCommands.literal("filtermode")
					.executes(context -> {
						context.getSource().sendFeedback(Component.literal(
								"[MarketUtils] Current filter mode: " + MarketUtilsConfig.getFilterMode()));
						return Command.SINGLE_SUCCESS;
					})
					.then(ClientCommands.argument("value", StringArgumentType.word())
							.executes(context -> {
								String requested = StringArgumentType.getString(context, "value");
								try {
									FilterMode filterMode = FilterMode.valueOf(requested.toUpperCase());
									MarketUtilsConfig.setFilterMode(filterMode);
									ProfitRenderer.clearCache();
									context.getSource().sendFeedback(Component.literal(
											"[MarketUtils] Filter mode set to " + filterMode));
								} catch (IllegalArgumentException e) {
									context.getSource().sendError(Component.literal(
											"[MarketUtils] Unknown filter mode. Valid modes: PROFIT_ONLY, ROI_ONLY, BOTH"));
								}
								return Command.SINGLE_SUCCESS;
							}));

			dispatcher.register(ClientCommands.literal("marketutils")
					.then(modeCommand)
					.then(profitableOnlyCommand)
					.then(minProfitCommand)
					.then(minRoiCommand)
					.then(filterModeCommand));
		});
	}
}
