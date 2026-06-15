package com.nhtrainer.inputtweaks;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AimTrainerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(NhAimTrainerPlugin.class);
		RuneLite.main(args);
	}
}
