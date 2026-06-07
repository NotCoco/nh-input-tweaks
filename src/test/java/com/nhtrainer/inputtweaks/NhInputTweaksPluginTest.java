package com.nhtrainer.inputtweaks;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class NhInputTweaksPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(NhInputTweaksPlugin.class);
		RuneLite.main(args);
	}
}
