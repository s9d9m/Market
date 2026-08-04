# MarketUtils

A Fabric 26.1.2 client-side mod for Hypixel Skyblock that evaluates Auction House listings at a glance.

## What it does

When you open an Auction House or BIN screen, MarketUtils compares each item's **listing price** against the **estimated item value** (from SkyHanni or similar mods) and draws a colored **border** around each slot:

| Border Color | Meaning |
|-------------|---------|
| Deep green | BIN price is far below estimated value (50%+ margin) |
| Light green | BIN price is moderately below estimated value |
| Yellow | BIN price is within 3% of estimated value (fair price) |
| Orange | BIN price is moderately above estimated value |
| Deep red | BIN price is far above estimated value (50%+ overpay) |

The border is drawn on top of the slot edges so SkyHanni's rarity backgrounds (pink for mythic, yellow for legendary, blue for rare) remain fully visible in the center.

A tooltip line is appended when hovering, showing the percentage and coin difference.

## Requirements

- Fabric Loader >= 0.18.4
- Fabric API 0.155.2+26.1.2
- Minecraft 26.1.2
- Java 25
- SkyHanni (provides the "Estimated Item Value" tooltip line this mod reads)

## Build

```powershell
.\gradlew build
```

Output: `build\libs\marketutils-1.0.0.jar`

Copy to your Fabric `mods` folder and launch Minecraft.

## License

CC BY-NC-SA 4.0 (with Commercial Use & Modpack Policy)
