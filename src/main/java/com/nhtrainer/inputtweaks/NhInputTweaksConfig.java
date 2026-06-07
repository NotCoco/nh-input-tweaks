package com.nhtrainer.inputtweaks;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("nhinputtweaks")
public interface NhInputTweaksConfig extends Config
{
	@Range(
		min = 25,
		max = 95
	)
	@ConfigItem(
		keyName = "clickedItemBrightness",
		name = "Clicked item brightness",
		description = "Brightness percentage for clicked item feedback. Lower values are darker.",
		position = 1
	)
	default int clickedItemBrightness()
	{
		return 65;
	}
}
