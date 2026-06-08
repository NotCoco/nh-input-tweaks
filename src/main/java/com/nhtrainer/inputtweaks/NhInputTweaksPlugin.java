package com.nhtrainer.inputtweaks;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "NH Input Tweaks",
	description = "Adds fast F-key tab switching.",
	tags = {"nh", "input", "tabs"},
	enabledByDefault = true
)
public class NhInputTweaksPlugin extends Plugin
{
	private static final int TOPLEVEL_KEYPRESS_SCRIPT_ID = 905;
	private static final int TOPLEVEL_FIXED_LAYOUT_KEY = 1129;
	private static final int TOPLEVEL_RESIZABLE_LAYOUT_KEY = 1130;
	private static final int TOPLEVEL_BOTTOM_LINE_LAYOUT_KEY = 1131;
	private static final int TOPLEVEL_DISPLAY_LAYOUT_KEY = 1132;
	private static final int TOPLEVEL_MOBILE_LAYOUT_KEY = 1745;
	private static final int TOPLEVEL_SPECTATOR_LAYOUT_KEY = 139;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	private final KeyListener fastTabKeyListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			handleFastTabKeyPressed(e);
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	};

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(fastTabKeyListener);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(fastTabKeyListener);
	}

	private void handleFastTabKeyPressed(KeyEvent keyEvent)
	{
		int keyCode = keyEvent.getKeyCode();
		if (keyCode < KeyEvent.VK_F1 || keyCode > KeyEvent.VK_F12 || keyEvent.isConsumed())
		{
			return;
		}

		int layoutKey = topLevelLayoutKey();
		if (layoutKey < 0)
		{
			return;
		}

		int jagexKeyCode = keyCode - KeyEvent.VK_F1 + 1;
		keyEvent.consume();
		clientThread.invoke(() -> runFastTabScript(jagexKeyCode, layoutKey));
	}

	private void runFastTabScript(int jagexKeyCode, int layoutKey)
	{
		try
		{
			client.runScript(TOPLEVEL_KEYPRESS_SCRIPT_ID, jagexKeyCode, layoutKey, 1);
		}
		catch (Exception ex)
		{
			log.warn("Fast F-key tab script failed for key {} layout {}", jagexKeyCode, layoutKey, ex);
		}
	}

	private int topLevelLayoutKey()
	{
		switch (client.getTopLevelInterfaceId())
		{
			case InterfaceID.TOPLEVEL:
				return TOPLEVEL_FIXED_LAYOUT_KEY;
			case InterfaceID.TOPLEVEL_OSRS_STRETCH:
				return TOPLEVEL_RESIZABLE_LAYOUT_KEY;
			case InterfaceID.TOPLEVEL_PRE_EOC:
				return TOPLEVEL_BOTTOM_LINE_LAYOUT_KEY;
			case InterfaceID.TOPLEVEL_DISPLAY:
				return TOPLEVEL_DISPLAY_LAYOUT_KEY;
			case InterfaceID.TOPLEVEL_OSM:
				return TOPLEVEL_MOBILE_LAYOUT_KEY;
			case InterfaceID.TOPLEVEL_SPECTATOR:
				return TOPLEVEL_SPECTATOR_LAYOUT_KEY;
			default:
				return -1;
		}
	}
}
