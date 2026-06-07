# NH Input Tweaks

RuneLite plugin for two small input-feel changes:

- Runs the game's top-level F-key tab script immediately when `F1` through `F12` are pressed.
- Draws item-shaped visual feedback over the clicked inventory item.

## Settings

- `Clicked item brightness`: controls how dark the clicked item feedback appears. Lower values are darker. The default is `65`.

## Running

```powershell
.\gradlew.bat run
```

Java plugin changes require restarting the RuneLite development client.

For Jagex accounts, follow RuneLite's development-client login flow:
https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
