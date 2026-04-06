# FamilyPrices

Shows Auction House lowest BIN and Bazaar buy/sell prices directly in item tooltips.

## Features
- Lowest BIN price for AH items
- Buy + Sell price for Bazaar items
- Prices cached for 5 minutes, auto-refreshes
- Clean tooltip format with coin formatting (1.2m, 450k, etc.)

## Building

### Requirements
- Java 21 (same one Minecraft uses)
- Internet connection (first build downloads ~300MB of dependencies)

### Steps

1. Download and install the **Gradle wrapper** first:
   - Windows: Open the project folder, hold Shift + right-click → "Open PowerShell here"
   - Run: `./gradlew.bat build` (Windows) or `./gradlew build` (Mac/Linux)

2. The jar will appear at:
   `build/libs/FamilyPrices-1.0.0.jar`

3. Copy it to your Minecraft mods folder:
   `%AppData%/ModrinthApp/profiles/YOUR_PROFILE/mods/`

### Required mods
- Fabric Loader 0.16+
- Fabric API
- Fabric Language Kotlin

## Notes
- Prices update every 5 minutes automatically
- Works on any SkyBlock item with a valid ExtraAttributes.id
- No API key required (uses Moulberry's public endpoint for BIN, public Hypixel endpoint for Bazaar)
