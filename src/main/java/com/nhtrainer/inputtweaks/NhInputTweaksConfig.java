package com.nhtrainer.inputtweaks;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("nhinputtweaks")
public interface NhInputTweaksConfig extends Config
{
	String GROUP = "nhinputtweaks";
	String FAST_TABS_ENABLED = "fastTabsEnabled";
	String ITEM_DARKENING_AMOUNT = "itemDarkeningAmount";

	@ConfigItem(
		keyName = FAST_TABS_ENABLED,
		name = "Fast F-key tabs",
		description = "Runs the tab-switching F-key script immediately when an F-key is pressed.",
		position = 1
	)
	default boolean fastTabsEnabled()
	{
		return true;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = ITEM_DARKENING_AMOUNT,
		name = "Item darkening",
		description = "Darkening strength for clicked item feedback. Set to 0 to disable.",
		position = 2
	)
	default int itemDarkeningAmount()
	{
		return 35;
	}
}
