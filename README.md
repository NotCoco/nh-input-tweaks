# NH Input Tweaks

RuneLite plugin for two small input-feel changes:

- Runs the game's top-level F-key tab script immediately when `F1` through `F12` are pressed.
- Draws item-shaped visual feedback over the clicked inventory item.

## Settings

- `Fast F-key tabs`: enables or disables immediate F-key tab switching. Enabled by default.
- `Item darkening`: controls clicked item feedback strength from `0` to `100`. Set to `0` to disable it. The default is `35`.

The plugin also adds a RuneLite sidebar icon with the same quick controls.

## Running

```powershell
.\gradlew.bat run
```

Java plugin changes require restarting the RuneLite development client.

For Jagex accounts, follow RuneLite's development-client login flow:
https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
