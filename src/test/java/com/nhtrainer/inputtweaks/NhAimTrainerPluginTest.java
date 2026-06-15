package com.nhtrainer.inputtweaks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.junit.Test;

public class NhAimTrainerPluginTest
{
	@Test
	public void inventoryItemActionIsBlockedOnceAfterDirectPress() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);

		MenuOptionClicked itemAction = inventoryMenuEvent(MenuAction.ITEM_FIRST_OPTION, true);

		assertTrue(isInventoryAction(plugin, itemAction));
		assertFalse(isInventoryAction(plugin, itemAction));
	}

	@Test
	public void runeliteMenuActionsAreNotBlockedByDirectInventoryPress() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "inventoryDirectPressActive", true);
		setField(plugin, "inventoryDirectPressDragged", false);

		assertFalse(isInventoryAction(plugin, menuEvent(MenuAction.RUNELITE, false)));
		assertTrue((Boolean) getField(plugin, "inventoryDirectPressActive"));
	}

	@Test
	public void pendingInventoryBlockIgnoresNonInventoryAction() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);

		assertFalse(isInventoryAction(plugin, menuEvent(MenuAction.WALK, false)));
		assertTrue((Integer) getField(plugin, "pendingInventoryActionBlocks") == 1);
		assertTrue(isInventoryAction(plugin, inventoryMenuEvent(MenuAction.ITEM_FIRST_OPTION, true)));
	}

	@Test
	public void pendingInventoryBlockIgnoresNonInventoryItemOp() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);

		assertFalse(isInventoryAction(plugin, menuEvent(MenuAction.ITEM_FIRST_OPTION, true)));
		assertTrue((Integer) getField(plugin, "pendingInventoryActionBlocks") == 1);
		assertTrue(isInventoryAction(plugin, inventoryMenuEvent(MenuAction.ITEM_FIRST_OPTION, true)));
	}

	@Test
	public void inventoryFallbackConsumesNonInventoryActionAfterConsumedInventoryClick() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configEnabled());
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directInventoryFallbackBlockUntilNanos", System.nanoTime() + 600_000_000L);
		MenuOptionClicked action = new MenuOptionClicked(menuEntry(MenuAction.WIDGET_TYPE_1, false, 123456, -1, "Cast"));

		plugin.onMenuOptionClicked(action);

		assertTrue(action.isConsumed());
		assertTrue((Integer) getField(plugin, "pendingInventoryActionBlocks") == 0);
		assertTrue((Long) getField(plugin, "directInventoryFallbackBlockUntilNanos") == -1L);
	}

	@Test
	public void inventoryFallbackIgnoresRuneliteActions() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "directInventoryFallbackBlockUntilNanos", System.nanoTime() + 600_000_000L);

		assertFalse(isInventoryFallbackAction(plugin, menuEvent(MenuAction.RUNELITE, false)));
		assertTrue((Long) getField(plugin, "directInventoryFallbackBlockUntilNanos") > System.nanoTime());
	}

	@Test
	public void openingMenuClearsDirectClickBlocksSoMenuSelectionCanWork() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "client", clientWithOpenMenu(new Canvas(), new Rectangle(100, 100, 140, 80)));
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directInventoryFallbackBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directMarkedSpellBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directOverlayBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directGameWorldBlockUntilNanos", System.nanoTime() + 600_000_000L);

		plugin.onMenuOpened(new MenuOpened());

		assertFalse(isInventoryAction(plugin, inventoryMenuEvent(MenuAction.ITEM_FIRST_OPTION, true)));
		assertFalse(isInventoryFallbackAction(plugin, menuEvent(MenuAction.WIDGET_TYPE_1, false, 123456)));
		assertFalse(isMarkedSpellAction(plugin, menuEvent(MenuAction.WIDGET_TYPE_1, false, 123456)));
		assertFalse(isOverlayClickAction(plugin, menuEvent(MenuAction.WALK, false)));
		assertFalse(isGameWorldClickAction(plugin, menuEvent(MenuAction.WALK, false)));
	}

	@Test
	public void aimBoxMenuIsOfferedOnlyForInventoryAndSpellbookEntries() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();

		assertTrue(shouldOfferAimBox(plugin, menuEntry(MenuAction.ITEM_FIRST_OPTION, false, InterfaceID.Inventory.ITEMS, -1, "Wear")));
		assertTrue(shouldOfferAimBox(plugin, menuEntry(MenuAction.WIDGET_TYPE_1, false, InterfaceID.MagicSpellbook.UNIVERSE, -1, "Cast")));
		assertFalse(shouldOfferAimBox(plugin, menuEntry(MenuAction.WALK, false, -1, -1, "Walk here")));
		assertFalse(shouldOfferAimBox(plugin, menuEntry(MenuAction.RUNELITE, false, InterfaceID.Inventory.ITEMS, -1, "Aim box")));
	}

	@Test
	public void duplicateAimBoxCheckOnlyCountsRuneliteAimBoxEntries() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		MenuEntry source = menuEntry(MenuAction.ITEM_FIRST_OPTION, false, InterfaceID.Inventory.ITEMS, 4151, "Wear");
		MenuEntry unrelatedSameLabel = menuEntry(MenuAction.WIDGET_TYPE_1, false, InterfaceID.Inventory.ITEMS, 4151, "Aim box");
		setField(plugin, "client", clientWithMenuEntries(unrelatedSameLabel));

		assertFalse(aimBoxEntryAlreadyPresent(plugin, source));

		MenuEntry runeliteSameLabel = menuEntry(MenuAction.RUNELITE, false, InterfaceID.Inventory.ITEMS, 4151, "Aim box");
		setField(plugin, "client", clientWithMenuEntries(runeliteSameLabel));

		assertTrue(aimBoxEntryAlreadyPresent(plugin, source));
	}

	@Test
	public void menuEntryAddedCreatesInventoryAimBoxOption() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		CapturingMenuEntry created = new CapturingMenuEntry();
		setField(plugin, "client", clientWithCreatedMenuEntry(created.proxy()));
		setField(plugin, "config", configEnabled());
		MenuEntry source = menuEntry(MenuAction.ITEM_FIRST_OPTION, false, InterfaceID.Inventory.ITEMS, 4151, "Wear", 0);

		plugin.onMenuEntryAdded(new MenuEntryAdded(source));

		assertTrue("Aim box".equals(created.option));
		assertTrue(created.type == MenuAction.RUNELITE);
		assertTrue(created.param0 == 0);
		assertTrue(created.param1 == InterfaceID.Inventory.ITEMS);
		assertTrue(created.itemId == 4151);
		assertTrue(created.onClick != null);
	}

	@Test
	public void inventoryAimBoxCallbackUsesCapturedSourceTarget() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		CapturingMenuEntry created = new CapturingMenuEntry();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, 4151, bounds);
		setField(plugin, "client", clientWithCreatedMenuEntryAndWidget(created.proxy(), InterfaceID.Inventory.ITEMS, inventoryWidget(children)));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configEnabled());
		MenuEntry source = menuEntry(MenuAction.ITEM_FIRST_OPTION, false, InterfaceID.Inventory.ITEMS, 4151, "Wear", 0);

		plugin.onMenuEntryAdded(new MenuEntryAdded(source));
		created.onClick.accept(created.proxy());

		assertTrue(aimBoxList(plugin, "inventoryBoxes").size() == 1);
	}

	@Test
	public void repeatedInventoryMenuRowsOnlyCreateOneAimBoxOption() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		CapturingMenuEntry created = new CapturingMenuEntry();
		setField(plugin, "client", clientWithCreatedMenuEntry(created.proxy()));
		setField(plugin, "config", configEnabled());
		MenuEntry first = menuEntry(MenuAction.ITEM_FIRST_OPTION, false, InterfaceID.Inventory.ITEMS, 4151, "Wear", 0);
		MenuEntry second = menuEntry(MenuAction.ITEM_SECOND_OPTION, false, InterfaceID.Inventory.ITEMS, 4151, "Remove", 0);

		plugin.onMenuEntryAdded(new MenuEntryAdded(first));
		plugin.onMenuEntryAdded(new MenuEntryAdded(second));

		assertTrue(created.setOptionCalls == 1);
		assertTrue("Aim box".equals(created.option));
	}

	@Test
	public void menuEntryAddedCreatesSpellAimBoxOption() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		CapturingMenuEntry created = new CapturingMenuEntry();
		setField(plugin, "client", clientWithCreatedMenuEntry(created.proxy()));
		setField(plugin, "config", configEnabled());
		MenuEntry source = menuEntry(MenuAction.WIDGET_TYPE_1, false, InterfaceID.MagicSpellbook.UNIVERSE, -1, "Cast");

		plugin.onMenuEntryAdded(new MenuEntryAdded(source));

		assertTrue("Aim box".equals(created.option));
		assertTrue(created.type == MenuAction.RUNELITE);
		assertTrue(created.param1 == InterfaceID.MagicSpellbook.UNIVERSE);
		assertTrue(created.onClick != null);
	}

	@Test
	public void spellAimBoxCallbackUsesCapturedSourceTarget() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		CapturingMenuEntry created = new CapturingMenuEntry();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		setField(plugin, "client", clientWithCreatedMenuEntryAndWidget(created.proxy(), InterfaceID.MagicSpellbook.UNIVERSE, widget(InterfaceID.MagicSpellbook.UNIVERSE, bounds)));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configEnabled());
		MenuEntry source = menuEntry(MenuAction.WIDGET_TYPE_1, false, InterfaceID.MagicSpellbook.UNIVERSE, -1, "Cast");

		plugin.onMenuEntryAdded(new MenuEntryAdded(source));
		created.onClick.accept(created.proxy());

		assertTrue(aimBoxList(plugin, "spellBoxes").size() == 1);
	}

	@Test
	public void pianoModeDoesNotOfferAimBoxMenuEntries() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		CapturingMenuEntry created = new CapturingMenuEntry();
		setField(plugin, "client", clientWithCreatedMenuEntry(created.proxy()));
		setField(plugin, "config", configPianoEnabled());
		MenuEntry source = menuEntry(MenuAction.ITEM_FIRST_OPTION, false, InterfaceID.Inventory.ITEMS, 4151, "Wear", 0);

		plugin.onMenuEntryAdded(new MenuEntryAdded(source));

		assertTrue(created.option == null);
	}

	@Test
	public void aimBoxSelectionOnlyHandlesRuneliteMenuAction() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configEnabled());
		MenuOptionClicked action = menuEvent(MenuAction.WIDGET_TYPE_1, false, InterfaceID.MagicSpellbook.UNIVERSE);

		plugin.onMenuOptionClicked(action);

		assertFalse(action.isConsumed());
	}

	@Test
	public void aimBoxSelectionIgnoresRuneliteActionForNonTargetEntry() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configEnabled());
		MenuOptionClicked action = menuEvent(MenuAction.RUNELITE, false, -1);

		plugin.onMenuOptionClicked(action);

		assertFalse(action.isConsumed());
		assertTrue(aimBoxList(plugin, "inventoryBoxes").isEmpty());
		assertTrue(aimBoxList(plugin, "spellBoxes").isEmpty());
	}

	@Test
	public void aimBoxSelectionWithCallbackIsLeftForCallbackPath() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configEnabled());
		MenuOptionClicked action = new MenuOptionClicked(menuEntry(
			MenuAction.RUNELITE,
			false,
			InterfaceID.Inventory.ITEMS,
			4151,
			"Aim box",
			0,
			null,
			entry -> {}));

		plugin.onMenuOptionClicked(action);

		assertFalse(action.isConsumed());
		assertTrue(aimBoxList(plugin, "inventoryBoxes").isEmpty());
	}

	@Test
	public void aimBoxSelectionIsIgnoredInPianoMode() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configPianoEnabled());
		MenuOptionClicked action = menuEvent(MenuAction.RUNELITE, false, InterfaceID.MagicSpellbook.UNIVERSE);

		plugin.onMenuOptionClicked(action);

		assertFalse(action.isConsumed());
		assertTrue(aimBoxList(plugin, "inventoryBoxes").isEmpty());
		assertTrue(aimBoxList(plugin, "spellBoxes").isEmpty());
	}

	@Test
	public void runeliteAimBoxSelectionAddsInventoryBox() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, 4151, bounds);
		setField(plugin, "client", clientWithInventory(canvas, inventoryWidget(children)));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configEnabled());
		MenuOptionClicked action = new MenuOptionClicked(menuEntry(MenuAction.RUNELITE, false, InterfaceID.Inventory.ITEMS, 4151, "Aim box", 0));

		plugin.onMenuOptionClicked(action);

		assertTrue(action.isConsumed());
		assertTrue(aimBoxList(plugin, "inventoryBoxes").size() == 1);
		Object box = aimBoxList(plugin, "inventoryBoxes").get(0);
		assertTrue(bounds.equals(getNestedField(box, "lastBounds")));
		assertTrue(bounds.equals(getNestedField(box, "activeBounds")));
	}

	@Test
	public void runeliteAimBoxSelectionTogglesInventoryBoxOff() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, 4151, bounds);
		setField(plugin, "client", clientWithInventory(canvas, inventoryWidget(children)));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configEnabled());

		plugin.onMenuOptionClicked(new MenuOptionClicked(menuEntry(MenuAction.RUNELITE, false, InterfaceID.Inventory.ITEMS, 4151, "Aim box", 0)));
		plugin.onMenuOptionClicked(new MenuOptionClicked(menuEntry(MenuAction.RUNELITE, false, InterfaceID.Inventory.ITEMS, 4151, "Aim box", 0)));

		assertTrue(aimBoxList(plugin, "inventoryBoxes").isEmpty());
	}

	@Test
	public void runeliteAimBoxSelectionAddsSpellBox() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		setField(plugin, "client", clientWithWidget(canvas, InterfaceID.MagicSpellbook.UNIVERSE, widget(InterfaceID.MagicSpellbook.UNIVERSE, bounds)));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configEnabled());
		MenuOptionClicked action = menuEvent(MenuAction.RUNELITE, false, InterfaceID.MagicSpellbook.UNIVERSE);

		plugin.onMenuOptionClicked(action);

		assertTrue(action.isConsumed());
		assertTrue(aimBoxList(plugin, "spellBoxes").size() == 1);
		Object box = aimBoxList(plugin, "spellBoxes").get(0);
		assertTrue(bounds.equals(getNestedField(box, "lastBounds")));
		assertTrue(bounds.equals(getNestedField(box, "activeBounds")));
	}

	@Test
	public void runeliteAimBoxSelectionTogglesSpellBoxOff() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		setField(plugin, "client", clientWithWidget(canvas, InterfaceID.MagicSpellbook.UNIVERSE, widget(InterfaceID.MagicSpellbook.UNIVERSE, bounds)));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configEnabled());

		plugin.onMenuOptionClicked(menuEvent(MenuAction.RUNELITE, false, InterfaceID.MagicSpellbook.UNIVERSE));
		plugin.onMenuOptionClicked(menuEvent(MenuAction.RUNELITE, false, InterfaceID.MagicSpellbook.UNIVERSE));

		assertTrue(aimBoxList(plugin, "spellBoxes").isEmpty());
	}

	@Test
	public void markedInventoryAimDragIsConsumedWithoutTurningIntoItemDrag() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithCanvas(canvas));
		setField(plugin, "inventoryDirectPressActive", true);
		setField(plugin, "inventoryDirectPressDragged", false);
		setField(plugin, "markedInventoryAimPressActive", true);
		setField(plugin, "inventoryDirectPressX", 10);
		setField(plugin, "inventoryDirectPressY", 10);

		MouseEvent drag = new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), 0, 30, 30, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "trackInventoryDirectDrag", MouseEvent.class, drag);

		assertTrue(drag.isConsumed());
		assertFalse((Boolean) getField(plugin, "inventoryDirectPressDragged"));
	}

	@Test
	public void unmarkedInventoryDragDoesNotBlockItemAction() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithCanvas(canvas));
		setField(plugin, "config", configEnabled());
		setField(plugin, "inventorySlotSnapshot", inventorySlotArray(0, 4151, new Rectangle(0, 0, 32, 32)));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 10, 1, false, MouseEvent.BUTTON1);
		MouseEvent drag = new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), 0, 30, 30, 1, false, MouseEvent.BUTTON1);
		MouseEvent release = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 30, 30, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "trackInventoryDirectPress", MouseEvent.class, press);
		invoke(plugin, "trackInventoryDirectDrag", MouseEvent.class, drag);
		invoke(plugin, "trackInventoryDirectRelease", MouseEvent.class, release);

		assertFalse(drag.isConsumed());
		assertTrue((Integer) getField(plugin, "pendingInventoryActionBlocks") == 0);
		assertFalse(isInventoryAction(plugin, inventoryMenuEvent(MenuAction.ITEM_FIRST_OPTION, true)));
	}

	@Test
	public void newInventoryPressDoesNotClearPendingActionBlock() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithCanvas(canvas));
		setField(plugin, "config", configEnabled());
		setField(plugin, "inventorySlotSnapshot", inventorySlotArray(0, 4151, new Rectangle(0, 0, 32, 32)));
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 10, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "trackInventoryDirectPress", MouseEvent.class, press);

		assertTrue((Integer) getField(plugin, "pendingInventoryActionBlocks") == 1);
		assertTrue((Boolean) getField(plugin, "inventoryDirectPressActive"));
		assertTrue(isInventoryAction(plugin, inventoryMenuEvent(MenuAction.ITEM_FIRST_OPTION, true)));
	}

	@Test
	public void gameWorldActionsAreBlockedWhileGameWorldClickIsArmed() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "directGameWorldBlockUntilNanos", System.nanoTime() + 600_000_000L);

		assertTrue(isGameWorldClickAction(plugin, menuEvent(MenuAction.WALK, false)));
		assertTrue(isGameWorldClickAction(plugin, menuEvent(MenuAction.PLAYER_FIRST_OPTION, false)));
		assertFalse(isGameWorldClickAction(plugin, menuEvent(MenuAction.RUNELITE, false)));
		assertFalse(isGameWorldClickAction(plugin, menuEvent(MenuAction.ITEM_FIRST_OPTION, true)));
	}

	@Test
	public void missedGameWorldLeftPressIsConsumedAndArmsBlock() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithViewport(canvas, new Rectangle(10, 20, 300, 200)));
		setField(plugin, "config", configEnabled());

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 100, 100, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertTrue(press.isConsumed());
		assertTrue((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertTrue((Boolean) getField(plugin, "consumeTrainingClick"));
		assertTrue((Long) getField(plugin, "directGameWorldBlockUntilNanos") > System.nanoTime());
		assertTrue(isGameWorldClickAction(plugin, menuEvent(MenuAction.WALK, false)));
	}

	@Test
	public void pianoModeConsumesCanvasMissAndBlocksUnderlyingAction() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithViewport(canvas, new Rectangle(10, 20, 300, 200), true));
		setField(plugin, "config", configPianoEnabled());

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 5, 5, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertTrue(press.isConsumed());
		assertTrue((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertTrue((Boolean) getField(plugin, "consumeTrainingClick"));
		assertTrue((Long) getField(plugin, "directOverlayBlockUntilNanos") > System.nanoTime());
		assertTrue((Long) getField(plugin, "directGameWorldBlockUntilNanos") > System.nanoTime());
		assertTrue(isOverlayClickAction(plugin, menuEvent(MenuAction.WIDGET_TYPE_1, false, 123456)));
		assertTrue(isGameWorldClickAction(plugin, menuEvent(MenuAction.WALK, false)));
	}

	@Test
	public void pianoModeTileHitIncrementsScoreAndConsumesClick() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithViewport(canvas, new Rectangle(0, 0, 400, 300), true));
		setField(plugin, "config", configPianoEnabled());
		setField(plugin, "pianoRunning", true);
		setField(plugin, "nextPianoSpawnNanos", System.nanoTime() + 10_000_000_000L);
		aimBoxList(plugin, "pianoTiles").add(pianoTile(0, 20.0));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 40, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertTrue(press.isConsumed());
		assertTrue((Integer) getField(plugin, "pianoScore") == 1);
		assertTrue((Boolean) getField(plugin, "pianoRunning"));
	}

	@Test
	public void markedInventoryPressConsumesClickAndHidesBoxForGameTick() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, 4151, bounds);
		setField(plugin, "client", clientWithInventory(canvas, inventoryWidget(children)));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, bounds));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		Object box = aimBoxList(plugin, "inventoryBoxes").get(0);
		long hiddenUntilNanos = (Long) getNestedField(box, "hiddenUntilNanos");
		long remainingNanos = hiddenUntilNanos - System.nanoTime();
		assertTrue(press.isConsumed());
		assertTrue((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertTrue((Boolean) getField(plugin, "consumeTrainingClick"));
		assertTrue((Boolean) getField(plugin, "markedInventoryAimPressActive"));
		assertTrue((Long) getField(plugin, "directInventoryFallbackBlockUntilNanos") > System.nanoTime());
		assertTrue(remainingNanos > 500_000_000L);
		assertTrue(remainingNanos <= 600_000_000L);
	}

	@Test
	public void markedSpellPressConsumesClickAndBlocksSpellAction() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithWidget(canvas, 123456, widget(123456, new Rectangle(10, 10, 32, 32))));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "spellBoxes").add(widgetAimBox(123456, new Rectangle(10, 10, 32, 32)));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertTrue(press.isConsumed());
		assertTrue((Long) getField(plugin, "directMarkedSpellBlockUntilNanos") > System.nanoTime());
		assertTrue((Long) getField(plugin, "directOverlayBlockUntilNanos") > System.nanoTime());
		assertTrue(isMarkedSpellAction(plugin, menuEvent(MenuAction.WIDGET_TYPE_1, false, 123456)));
	}

	@Test
	public void hiddenSpellBoxStaysVisibleButDoesNotCountAsClickable() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		setField(plugin, "client", clientWithCanvas(canvas));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "spellBoxes").add(widgetAimBox(123456, bounds));
		invoke(plugin, "refreshAimBoxBounds");

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		Object box = aimBoxList(plugin, "spellBoxes").get(0);
		assertTrue(press.isConsumed());
		assertTrue((Long) getNestedField(box, "hiddenUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directMarkedSpellBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directOverlayBlockUntilNanos") > System.nanoTime());
	}

	@Test
	public void spellBoxReactivatesWhenSpellbookWidgetReturns() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Object box = widgetAimBox(123456, bounds);
		aimBoxList(plugin, "spellBoxes").add(box);
		setField(plugin, "config", configEnabled());

		setField(plugin, "client", clientWithCanvas(canvas));
		invoke(plugin, "refreshAimBoxBounds");
		assertTrue(getNestedField(box, "activeBounds") == null);
		assertTrue(bounds.equals(getNestedField(box, "lastBounds")));

		setField(plugin, "client", clientWithWidget(canvas, 123456, widget(123456, bounds)));
		invoke(plugin, "refreshAimBoxBounds");

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, false, MouseEvent.BUTTON1);
		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertTrue(press.isConsumed());
		assertTrue(getNestedField(box, "activeBounds") != null);
		assertTrue((Long) getNestedField(box, "hiddenUntilNanos") > System.nanoTime());
	}

	@Test
	public void hiddenSpellBoxDoesNotConsumeRightClickMenuPress() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithCanvas(canvas));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "spellBoxes").add(widgetAimBox(123456, new Rectangle(10, 10, 32, 32)));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, true, MouseEvent.BUTTON3);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertFalse(press.isConsumed());
		assertFalse((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertFalse((Boolean) getField(plugin, "consumeTrainingClick"));
	}

	@Test
	public void activeInventoryBoxDoesNotConsumeRightClickMenuPress() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, 4151, bounds);
		setField(plugin, "client", clientWithInventory(canvas, inventoryWidget(children)));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, bounds));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, true, MouseEvent.BUTTON3);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertFalse(press.isConsumed());
		assertFalse((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertFalse((Boolean) getField(plugin, "consumeTrainingClick"));
	}

	@Test
	public void activeSpellBoxDoesNotConsumeRightClickMenuPress() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		setField(plugin, "client", clientWithWidget(canvas, 123456, widget(123456, bounds)));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "spellBoxes").add(widgetAimBox(123456, bounds));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, true, MouseEvent.BUTTON3);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertFalse(press.isConsumed());
		assertFalse((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertFalse((Boolean) getField(plugin, "consumeTrainingClick"));
	}

	@Test
	public void hiddenInventoryBoxStaysVisibleButDoesNotCountAsClickable() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		setField(plugin, "client", clientWithCanvas(canvas));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, bounds));
		invoke(plugin, "refreshAimBoxBounds");

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		Object box = aimBoxList(plugin, "inventoryBoxes").get(0);
		assertTrue(press.isConsumed());
		assertTrue((Long) getNestedField(box, "hiddenUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directInventoryFallbackBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directOverlayBlockUntilNanos") > System.nanoTime());
	}

	@Test
	public void markedSpellBlockIgnoresRuneliteOverlayAction() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "directMarkedSpellBlockUntilNanos", System.nanoTime() + 600_000_000L);
		aimBoxList(plugin, "spellBoxes").add(widgetAimBox(123456, new Rectangle(10, 10, 32, 32)));

		assertFalse(isMarkedSpellAction(plugin, menuEvent(MenuAction.RUNELITE_OVERLAY, false, 123456)));
		assertTrue(isMarkedSpellAction(plugin, menuEvent(MenuAction.WIDGET_TYPE_1, false, 123456)));
	}

	@Test
	public void hiddenSpellWidgetKeepsLastOverlayBounds() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Rectangle lastBounds = new Rectangle(10, 10, 32, 32);
		Object box = widgetAimBox(123456, lastBounds);
		setField(plugin, "client", clientWithCanvas(new Canvas()));

		Rectangle resolved = (Rectangle) invoke(plugin, "resolveWidgetBounds", box.getClass(), box);

		assertTrue(lastBounds.equals(resolved));
		assertTrue(lastBounds.equals(getNestedField(box, "lastBounds")));
	}

	@Test
	public void readdingExistingSpellBoxRefreshesClickableBoundsWithoutDuplicating() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Object box = widgetAimBox(123456, new Rectangle(0, 0, 32, 32));
		setNestedField(box, "activeBounds", null);
		setNestedField(box, "hiddenUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "client", clientWithWidget(canvas, 123456, widget(123456, bounds)));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "spellBoxes").add(box);

		invoke(plugin, "addSpellAimBox", int.class, 123456);

		assertTrue(aimBoxList(plugin, "spellBoxes").size() == 1);
		assertTrue(bounds.equals(getNestedField(box, "lastBounds")));
		assertTrue(bounds.equals(getNestedField(box, "activeBounds")));
		assertTrue((Long) getNestedField(box, "hiddenUntilNanos") == -1L);
	}

	@Test
	public void inventoryAimBoxFollowsMovedItemSlot() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Rectangle originalBounds = new Rectangle(0, 0, 32, 32);
		Rectangle movedBounds = new Rectangle(160, 0, 32, 32);
		Object box = inventoryAimBox(0, 4151, originalBounds);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, -1, originalBounds);
		children[5] = inventoryItemWidget(5, 4151, movedBounds);
		setField(plugin, "client", clientWithInventory(new Canvas(), inventoryWidget(children)));

		Rectangle resolved = (Rectangle) invoke(plugin, "resolveInventoryBounds", box.getClass(), box);

		assertTrue(movedBounds.equals(resolved));
		assertTrue((Integer) getNestedField(box, "slotIndex") == 5);
		assertTrue(movedBounds.equals(getNestedField(box, "lastBounds")));
	}

	@Test
	public void readdingExistingInventoryBoxRefreshesClickableBoundsWithoutDuplicating() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, 4151, bounds);
		Object box = inventoryAimBox(0, 4151, new Rectangle(0, 0, 32, 32));
		setNestedField(box, "activeBounds", null);
		setNestedField(box, "hiddenUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "client", clientWithInventory(canvas, inventoryWidget(children)));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "inventoryBoxes").add(box);

		invoke(plugin, "addInventoryAimBox", new Class<?>[]{int.class, int.class}, 0, 4151);

		assertTrue(aimBoxList(plugin, "inventoryBoxes").size() == 1);
		assertTrue(bounds.equals(getNestedField(box, "lastBounds")));
		assertTrue(bounds.equals(getNestedField(box, "activeBounds")));
		assertTrue((Long) getNestedField(box, "hiddenUntilNanos") == -1L);
	}

	@Test
	public void overlayRendersAlwaysOnTopDynamicLayer() throws Exception
	{
		Overlay overlay = overlay(new NhAimTrainerPlugin());

		assertTrue(overlay.getLayer() == OverlayLayer.ALWAYS_ON_TOP);
		assertTrue(overlay.getPosition() == OverlayPosition.DYNAMIC);
	}

	@Test
	public void overlayDrawsTransparentBlackBox() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, new Rectangle(10, 10, 32, 32)));
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay(plugin).render(graphics);
		}
		finally
		{
			graphics.dispose();
		}

		int centerPixel = image.getRGB(20, 20);
		int alpha = (centerPixel >>> 24) & 0xff;
		int red = (centerPixel >>> 16) & 0xff;
		int green = (centerPixel >>> 8) & 0xff;
		int blue = centerPixel & 0xff;

		assertTrue(alpha > 0);
		assertTrue(alpha < 255);
		assertTrue(red == 0);
		assertTrue(green == 0);
		assertTrue(blue == 0);

		int borderPixel = image.getRGB(10, 10);
		int borderAlpha = (borderPixel >>> 24) & 0xff;
		int borderRed = (borderPixel >>> 16) & 0xff;
		int borderGreen = (borderPixel >>> 8) & 0xff;
		int borderBlue = borderPixel & 0xff;

		assertTrue(borderAlpha == 255);
		assertTrue(borderRed == 255);
		assertTrue(borderGreen == 255);
		assertTrue(borderBlue == 255);
	}

	@Test
	public void pianoModeRendersTilesInsteadOfSavedAimBoxes() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "client", clientWithViewport(new Canvas(), new Rectangle(0, 0, 100, 100), true));
		setField(plugin, "config", configPianoEnabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, new Rectangle(80, 80, 16, 16)));
		aimBoxList(plugin, "pianoTiles").add(pianoTile(0, 10.0));
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay(plugin).render(graphics);
		}
		finally
		{
			graphics.dispose();
		}

		assertTrue(((image.getRGB(6, 20) >>> 24) & 0xff) > 0);
		assertTrue(((image.getRGB(88, 88) >>> 24) & 0xff) == 0);
		assertTrue(((image.getRGB(50, 90) >>> 24) & 0xff) == 0);
	}

	@Test
	public void gameTargetsFadeInOverTime() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configEnabled());
		Object gameBox = gameAimBox(new Rectangle(10, 10, 32, 32));
		aimBoxList(plugin, "gameBoxes").add(gameBox);

		setNestedField(gameBox, "spawnedAtNanos", System.nanoTime() - 250_000_000L);
		int earlyAlpha = renderedAlphaAt(plugin, 20, 20);

		setNestedField(gameBox, "spawnedAtNanos", System.nanoTime() - 900_000_000L);
		int lateAlpha = renderedAlphaAt(plugin, 20, 20);

		assertTrue(earlyAlpha > 0);
		assertTrue(lateAlpha > earlyAlpha);
		assertTrue(lateAlpha < 255);
	}

	@Test
	public void pianoProgressionKeepsStartingDifficultyAndRampsTwentyPercentFaster() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();

		setField(plugin, "pianoScore", 0);
		double startingSpeed = (Double) invoke(plugin, "pianoPixelsPerSecond");
		long startingInterval = (Long) invoke(plugin, "pianoSpawnIntervalNanos");

		setField(plugin, "pianoScore", 10);
		double progressedSpeed = (Double) invoke(plugin, "pianoPixelsPerSecond");
		long progressedInterval = (Long) invoke(plugin, "pianoSpawnIntervalNanos");

		assertTrue(Math.abs(startingSpeed - 390.0) < 0.001);
		assertTrue(startingInterval == 405_000_000L);
		assertTrue(Math.abs(progressedSpeed - 438.0) < 0.001);
		assertTrue(progressedInterval == 297_000_000L);
	}

	@Test
	public void pianoSoundGainHonorsToggleAndSoftVolumeCurve() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();

		setField(plugin, "config", config(true, true, NhAimTrainerConfig.GameTargetShape.SQUARES, 36, false, 100));
		assertTrue((Double) invoke(plugin, "pianoSoundGain") == 0.0);

		setField(plugin, "config", config(true, true, NhAimTrainerConfig.GameTargetShape.SQUARES, 36, true, 0));
		assertTrue((Double) invoke(plugin, "pianoSoundGain") == 0.0);

		setField(plugin, "config", config(true, true, NhAimTrainerConfig.GameTargetShape.SQUARES, 36, true, 1));
		double onePercent = (Double) invoke(plugin, "pianoSoundGain");

		setField(plugin, "config", config(true, true, NhAimTrainerConfig.GameTargetShape.SQUARES, 36, true, 50));
		double half = (Double) invoke(plugin, "pianoSoundGain");

		setField(plugin, "config", config(true, true, NhAimTrainerConfig.GameTargetShape.SQUARES, 36, true, 100));
		double full = (Double) invoke(plugin, "pianoSoundGain");

		assertTrue(onePercent > 0.0);
		assertTrue(onePercent < 0.0001);
		assertTrue(half > onePercent);
		assertTrue(full > half);
		assertTrue(full <= 0.18);
	}

	@Test
	public void pianoShepardPitchClassRisesThenWrapsByOctave() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();

		double first = (Double) invoke(plugin, "pianoShepardPitchClassHz", double.class, 0.0);
		double second = (Double) invoke(plugin, "pianoShepardPitchClassHz", double.class, 1.0);
		double wrapped = (Double) invoke(plugin, "pianoShepardPitchClassHz", double.class, 12.0);

		assertTrue(second > first);
		assertTrue(Math.abs(first - wrapped) < 0.001);
	}

	@Test
	public void pianoClickPcmUsesGainAndCanBeSilent() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();

		byte[] silent = (byte[]) invoke(plugin, "buildPianoTileClickPcm", new Class<?>[]{int.class, double.class}, 0, 0.0);
		byte[] quiet = (byte[]) invoke(plugin, "buildPianoTileClickPcm", new Class<?>[]{int.class, double.class}, 0, 0.01);
		byte[] loud = (byte[]) invoke(plugin, "buildPianoTileClickPcm", new Class<?>[]{int.class, double.class}, 0, 0.18);

		assertTrue(silent.length == 0);
		assertTrue(quiet.length > 0);
		assertTrue(loud.length == quiet.length);
		assertTrue(maxAbsPcm16(loud) > maxAbsPcm16(quiet));
	}

	@Test
	public void randomGameTargetsUseConfiguredAverageSquareSize() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "client", clientWithViewport(new Canvas(), new Rectangle(0, 0, 500, 350), true));
		setField(plugin, "config", config(true, false, NhAimTrainerConfig.GameTargetShape.SQUARES, 60));

		for (int i = 0; i < 20; i++)
		{
			Rectangle bounds = (Rectangle) invoke(plugin, "randomViewportTargetBounds");
			assertTrue(bounds.width == bounds.height);
			assertTrue(bounds.width >= 49);
			assertTrue(bounds.width <= 71);
		}
	}

	@Test
	public void circleGameTargetsUseCircularHitTest() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", config(true, false, NhAimTrainerConfig.GameTargetShape.CIRCLES, 40));
		Object gameBox = gameAimBox(new Rectangle(10, 10, 40, 40));

		assertTrue(containsGameBox(plugin, gameBox, 30, 30));
		assertFalse(containsGameBox(plugin, gameBox, 10, 10));
	}

	@Test
	public void squareGameTargetsUseSquareHitTest() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", config(true, false, NhAimTrainerConfig.GameTargetShape.SQUARES, 40));
		Object gameBox = gameAimBox(new Rectangle(10, 10, 40, 40));

		assertTrue(containsGameBox(plugin, gameBox, 30, 30));
		assertTrue(containsGameBox(plugin, gameBox, 10, 10));
	}

	@Test
	public void gameTargetSizeChangeWhileInPianoModeDoesNotSpawnNormalTargets() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "client", clientWithViewport(new Canvas(), new Rectangle(0, 0, 500, 350), true));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configPianoEnabled());
		aimBoxList(plugin, "gameBoxes").add(gameAimBox(new Rectangle(50, 50, 30, 30)));
		ConfigChanged event = new ConfigChanged();
		event.setGroup(NhAimTrainerConfig.GROUP);
		event.setKey(NhAimTrainerConfig.GAME_TARGET_AVERAGE_SIZE);
		event.setOldValue("36");
		event.setNewValue("60");

		plugin.onConfigChanged(event);

		assertTrue(aimBoxList(plugin, "gameBoxes").isEmpty());
	}

	@Test
	public void menuEntryAddedCreatesActorAimBoxOption() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		CapturingMenuEntry created = new CapturingMenuEntry();
		MutableActor actor = mutableActor(new Rectangle(100, 100, 20, 44));
		setField(plugin, "client", clientWithCreatedMenuEntry(created.proxy()));
		setField(plugin, "config", configEnabled());
		MenuEntry source = menuEntry(MenuAction.NPC_FIRST_OPTION, false, -1, -1, "Attack", 12, actor.proxy);

		plugin.onMenuEntryAdded(new MenuEntryAdded(source));

		assertTrue("Aim box".equals(created.option));
		assertTrue(created.type == MenuAction.RUNELITE);
		assertTrue(created.param0 == 12);
		assertTrue(created.onClick != null);
	}

	@Test
	public void actorAimBoxUsesActorConvexHullAndUpdates() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		MutableActor actor = mutableActor(new Rectangle(100, 100, 20, 44));
		setField(plugin, "config", configEnabled());

		invoke(plugin, "addActorAimBox", Actor.class, actor.proxy);
		Object box = aimBoxList(plugin, "actorBoxes").get(0);
		assertTrue(((Shape) getNestedField(box, "activeShape")).getBounds().equals(new Rectangle(100, 100, 20, 44)));

		actor.shape = new Rectangle(120, 90, 18, 36);
		invoke(plugin, "refreshAimBoxBounds");
		assertTrue(((Shape) getNestedField(box, "activeShape")).getBounds().equals(new Rectangle(120, 90, 18, 36)));

		actor.shape = null;
		invoke(plugin, "refreshAimBoxBounds");
		assertTrue(getNestedField(box, "activeShape") == null);
	}

	@Test
	public void runeliteAimBoxSelectionTogglesActorBoxOff() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		MutableActor actor = mutableActor(new Rectangle(100, 100, 20, 44));
		setField(plugin, "clientThread", new ImmediateClientThread());
		setField(plugin, "config", configEnabled());

		plugin.onMenuOptionClicked(new MenuOptionClicked(menuEntry(MenuAction.RUNELITE, false, -1, -1, "Aim box", 12, actor.proxy)));
		plugin.onMenuOptionClicked(new MenuOptionClicked(menuEntry(MenuAction.RUNELITE, false, -1, -1, "Aim box", 12, actor.proxy)));

		assertTrue(aimBoxList(plugin, "actorBoxes").isEmpty());
	}

	@Test
	public void actorAimBoxRefreshesDuringOverlayRenderForCameraMovement() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		MutableActor actor = mutableActor(new Rectangle(10, 10, 12, 30));
		setField(plugin, "config", configEnabled());
		invoke(plugin, "addActorAimBox", Actor.class, actor.proxy);
		Object box = aimBoxList(plugin, "actorBoxes").get(0);

		actor.shape = new Rectangle(44, 22, 16, 38);
		BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay(plugin).render(graphics);
		}
		finally
		{
			graphics.dispose();
		}

		assertTrue(((Shape) getNestedField(box, "activeShape")).getBounds().equals(new Rectangle(44, 22, 16, 38)));
		assertTrue(((image.getRGB(48, 28) >>> 24) & 0xff) > 0);
		assertTrue(((image.getRGB(12, 12) >>> 24) & 0xff) == 0);
	}

	@Test
	public void actorAimBoxPressConsumesClickAndBlocksUnderlyingWorldAction() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		MutableActor actor = mutableActor(new Rectangle(30, 30, 20, 44));
		setField(plugin, "client", clientWithCanvas(canvas));
		setField(plugin, "config", configEnabled());
		invoke(plugin, "addActorAimBox", Actor.class, actor.proxy);

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 36, 42, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		Object box = aimBoxList(plugin, "actorBoxes").get(0);
		assertTrue(press.isConsumed());
		assertTrue((Long) getNestedField(box, "hiddenUntilNanos") > System.nanoTime());
		assertTrue((Long) getField(plugin, "directOverlayBlockUntilNanos") > System.nanoTime());
		assertTrue((Long) getField(plugin, "directGameWorldBlockUntilNanos") > System.nanoTime());
	}

	@Test
	public void resetClearsMarkedBoxesAndRegeneratesGameTargetsWhenEnabled() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "client", clientWithViewport(new Canvas(), new Rectangle(0, 0, 500, 350), true));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, new Rectangle(10, 10, 32, 32)));
		aimBoxList(plugin, "spellBoxes").add(widgetAimBox(123456, new Rectangle(50, 50, 32, 32)));
		aimBoxList(plugin, "gameBoxes").add(gameAimBox(new Rectangle(100, 100, 30, 45)));
		setField(plugin, "consumeTrainingRelease", true);
		setField(plugin, "consumeTrainingClick", true);
		setField(plugin, "markedInventoryAimPressActive", true);
		setField(plugin, "inventoryDirectPressActive", true);
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directInventoryFallbackBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directMarkedSpellBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directOverlayBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directGameWorldBlockUntilNanos", System.nanoTime() + 600_000_000L);

		invoke(plugin, "resetAimBoxesOnClientThread");

		assertTrue(aimBoxList(plugin, "inventoryBoxes").isEmpty());
		assertTrue(aimBoxList(plugin, "spellBoxes").isEmpty());
		assertTrue(aimBoxList(plugin, "gameBoxes").size() == 2);
		assertFalse((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertFalse((Boolean) getField(plugin, "consumeTrainingClick"));
		assertFalse((Boolean) getField(plugin, "markedInventoryAimPressActive"));
		assertFalse((Boolean) getField(plugin, "inventoryDirectPressActive"));
		assertTrue((Integer) getField(plugin, "pendingInventoryActionBlocks") == 0);
		assertTrue((Long) getField(plugin, "pendingInventoryActionBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directInventoryFallbackBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directMarkedSpellBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directOverlayBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directGameWorldBlockUntilNanos") == -1L);
	}

	@Test
	public void gameTargetPressConsumesClickAndMaintainsTwoTargets() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithViewport(canvas, new Rectangle(0, 0, 500, 350), true));
		setField(plugin, "config", configEnabled());
		aimBoxList(plugin, "gameBoxes").add(gameAimBox(new Rectangle(50, 50, 30, 45)));
		aimBoxList(plugin, "gameBoxes").add(gameAimBox(new Rectangle(150, 50, 30, 45)));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 55, 55, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		Object clickedBox = aimBoxList(plugin, "gameBoxes").get(0);
		long hiddenUntilNanos = (Long) getNestedField(clickedBox, "hiddenUntilNanos");
		long spawnedAtNanos = (Long) getNestedField(clickedBox, "spawnedAtNanos");
		assertTrue(press.isConsumed());
		assertTrue(hiddenUntilNanos > System.nanoTime());
		assertTrue(spawnedAtNanos == hiddenUntilNanos);
		assertTrue(aimBoxList(plugin, "gameBoxes").size() == 2);
	}

	@Test
	public void localPianoScoresIgnoreCorruptRows() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();

		List<Object> scores = parsePianoScores(plugin, "3|1000\nbad\n-1|2000\n7|3000");

		assertTrue(scores.size() == 2);
		assertTrue((Integer) getNestedField(scores.get(0), "score") == 3);
		assertTrue((Long) getNestedField(scores.get(0), "timestampMillis") == 1000L);
		assertTrue((Integer) getNestedField(scores.get(1), "score") == 7);
		assertTrue((Long) getNestedField(scores.get(1), "timestampMillis") == 3000L);
	}

	@Test
	public void gameTargetsClearWhenLoggedOutAndRegenerateWhenLoggedIn() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		aimBoxList(plugin, "gameBoxes").add(gameAimBox(new Rectangle(50, 50, 30, 45)));
		setField(plugin, "client", clientWithViewport(canvas, new Rectangle(0, 0, 500, 350), false));

		invoke(plugin, "ensureGameTargets");

		assertTrue(aimBoxList(plugin, "gameBoxes").isEmpty());

		setField(plugin, "client", clientWithViewport(canvas, new Rectangle(0, 0, 500, 350), true));

		invoke(plugin, "ensureGameTargets");

		assertTrue(aimBoxList(plugin, "gameBoxes").size() == 2);
	}

	@Test
	public void openMenuInteractionIsNotConsumedAsGameWorldClick() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		setField(plugin, "client", clientWithViewport(canvas, new Rectangle(10, 20, 300, 200)));
		setField(plugin, "config", configEnabled());
		setField(plugin, "menuOpen", true);

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 100, 100, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertFalse(press.isConsumed());
		assertFalse((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertFalse((Boolean) getField(plugin, "consumeTrainingClick"));
	}

	@Test
	public void disabledTrainerDoesNotConsumeMarkedBoxPresses() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		Canvas canvas = new Canvas();
		Rectangle bounds = new Rectangle(10, 10, 32, 32);
		Widget[] children = new Widget[28];
		children[0] = inventoryItemWidget(0, 4151, bounds);
		setField(plugin, "client", clientWithInventory(canvas, inventoryWidget(children)));
		setField(plugin, "config", configDisabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, bounds));

		MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 20, 20, 1, false, MouseEvent.BUTTON1);

		invoke(plugin, "handleTrainingMousePress", MouseEvent.class, press);

		assertFalse(press.isConsumed());
		assertFalse((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertFalse((Boolean) getField(plugin, "consumeTrainingClick"));
	}

	@Test
	public void disabledTrainerDoesNotRenderAimBoxes() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configDisabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, new Rectangle(10, 10, 32, 32)));
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay(plugin).render(graphics);
		}
		finally
		{
			graphics.dispose();
		}

		assertTrue(image.getRGB(20, 20) == 0);
	}

	@Test
	public void disablingTrainerClearsTransientInputStateButKeepsAimBoxes() throws Exception
	{
		NhAimTrainerPlugin plugin = new NhAimTrainerPlugin();
		setField(plugin, "config", configDisabled());
		aimBoxList(plugin, "inventoryBoxes").add(inventoryAimBox(0, 4151, new Rectangle(10, 10, 32, 32)));
		aimBoxList(plugin, "spellBoxes").add(widgetAimBox(123456, new Rectangle(50, 50, 32, 32)));
		setField(plugin, "consumeTrainingRelease", true);
		setField(plugin, "consumeTrainingClick", true);
		setField(plugin, "markedInventoryAimPressActive", true);
		setField(plugin, "inventoryDirectPressActive", true);
		setField(plugin, "pendingInventoryActionBlocks", 1);
		setField(plugin, "pendingInventoryActionBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directInventoryFallbackBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directMarkedSpellBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directOverlayBlockUntilNanos", System.nanoTime() + 600_000_000L);
		setField(plugin, "directGameWorldBlockUntilNanos", System.nanoTime() + 600_000_000L);
		ConfigChanged event = new ConfigChanged();
		event.setGroup(NhAimTrainerConfig.GROUP);
		event.setKey(NhAimTrainerConfig.ENABLED);
		event.setOldValue("true");
		event.setNewValue("false");

		plugin.onConfigChanged(event);

		assertTrue(aimBoxList(plugin, "inventoryBoxes").size() == 1);
		assertTrue(aimBoxList(plugin, "spellBoxes").size() == 1);
		assertFalse((Boolean) getField(plugin, "consumeTrainingRelease"));
		assertFalse((Boolean) getField(plugin, "consumeTrainingClick"));
		assertFalse((Boolean) getField(plugin, "markedInventoryAimPressActive"));
		assertFalse((Boolean) getField(plugin, "inventoryDirectPressActive"));
		assertTrue((Integer) getField(plugin, "pendingInventoryActionBlocks") == 0);
		assertTrue((Long) getField(plugin, "pendingInventoryActionBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directInventoryFallbackBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directMarkedSpellBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directOverlayBlockUntilNanos") == -1L);
		assertTrue((Long) getField(plugin, "directGameWorldBlockUntilNanos") == -1L);
	}

	private static boolean isInventoryAction(NhAimTrainerPlugin plugin, MenuOptionClicked event) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("isInventoryAction", MenuOptionClicked.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, event);
	}

	private static boolean isInventoryFallbackAction(NhAimTrainerPlugin plugin, MenuOptionClicked event) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("isInventoryFallbackAction", MenuOptionClicked.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, event);
	}

	private static boolean isGameWorldClickAction(NhAimTrainerPlugin plugin, MenuOptionClicked event) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("isGameWorldClickAction", MenuOptionClicked.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, event);
	}

	private static boolean isMarkedSpellAction(NhAimTrainerPlugin plugin, MenuOptionClicked event) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("isMarkedSpellAction", MenuOptionClicked.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, event);
	}

	private static boolean isOverlayClickAction(NhAimTrainerPlugin plugin, MenuOptionClicked event) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("isOverlayClickAction", MenuOptionClicked.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, event);
	}

	private static boolean shouldOfferAimBox(NhAimTrainerPlugin plugin, MenuEntry entry) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("shouldOfferAimBox", MenuEntry.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, entry);
	}

	private static boolean aimBoxEntryAlreadyPresent(NhAimTrainerPlugin plugin, MenuEntry entry) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("aimBoxEntryAlreadyPresent", MenuEntry.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, entry);
	}

	private static boolean containsGameBox(NhAimTrainerPlugin plugin, Object gameBox, int x, int y) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("containsGameBox", gameBox.getClass(), int.class, int.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(plugin, gameBox, x, y);
	}

	@SuppressWarnings("unchecked")
	private static List<Object> parsePianoScores(NhAimTrainerPlugin plugin, String raw) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod("parsePianoScores", String.class);
		method.setAccessible(true);
		return (List<Object>) method.invoke(plugin, raw);
	}

	private static Object invoke(Object target, String methodName, Class<?> argumentType, Object argument) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod(methodName, argumentType);
		method.setAccessible(true);
		return method.invoke(target, argument);
	}

	private static Object invoke(Object target, String methodName, Class<?>[] argumentTypes, Object... arguments) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod(methodName, argumentTypes);
		method.setAccessible(true);
		return method.invoke(target, arguments);
	}

	private static Object invoke(Object target, String methodName) throws Exception
	{
		Method method = NhAimTrainerPlugin.class.getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method.invoke(target);
	}

	private static Client clientWithCanvas(Canvas canvas)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			if ("getCanvas".equals(method.getName()))
			{
				return canvas;
			}
			return defaultValue(method.getReturnType());
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static Client clientWithViewport(Canvas canvas, Rectangle viewport)
	{
		return clientWithViewport(canvas, viewport, false);
	}

	private static Client clientWithViewport(Canvas canvas, Rectangle viewport, boolean loggedIn)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getCanvas":
					return canvas;
				case "getViewportXOffset":
					return viewport.x;
				case "getViewportYOffset":
					return viewport.y;
				case "getViewportWidth":
					return viewport.width;
				case "getViewportHeight":
					return viewport.height;
				case "getGameState":
					return loggedIn ? GameState.LOGGED_IN : defaultValue(method.getReturnType());
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static Client clientWithInventory(Canvas canvas, Widget inventory)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getCanvas":
					return canvas;
				case "getWidget":
					if (args != null && args.length == 1 && args[0] instanceof Integer && (Integer) args[0] == InterfaceID.Inventory.ITEMS)
					{
						return inventory;
					}
					return null;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static Client clientWithWidget(Canvas canvas, int widgetId, Widget widget)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getCanvas":
					return canvas;
				case "getWidget":
					if (args != null && args.length == 1 && args[0] instanceof Integer && (Integer) args[0] == widgetId)
					{
						return widget;
					}
					return null;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static Client clientWithOpenMenu(Canvas canvas, Rectangle menuBounds)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getCanvas":
					return canvas;
				case "isMenuOpen":
					return true;
				case "getMenuX":
					return menuBounds.x;
				case "getMenuY":
					return menuBounds.y;
				case "getMenuWidth":
					return menuBounds.width;
				case "getMenuHeight":
					return menuBounds.height;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static Client clientWithMenuEntries(MenuEntry... entries)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			if ("getMenuEntries".equals(method.getName()))
			{
				return entries;
			}
			return defaultValue(method.getReturnType());
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static Client clientWithCreatedMenuEntry(MenuEntry created, MenuEntry... entries)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "createMenuEntry":
					return created;
				case "getMenuEntries":
					return entries;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static Client clientWithCreatedMenuEntryAndWidget(MenuEntry created, int widgetId, Widget widget)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "createMenuEntry":
					return created;
				case "getMenuEntries":
					return new MenuEntry[0];
				case "getWidget":
					if (args != null && args.length == 1 && args[0] instanceof Integer && (Integer) args[0] == widgetId)
					{
						return widget;
					}
					return null;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			handler);
	}

	private static NhAimTrainerConfig configEnabled()
	{
		return config(true, false);
	}

	private static NhAimTrainerConfig configDisabled()
	{
		return config(false, false);
	}

	private static NhAimTrainerConfig configPianoEnabled()
	{
		return config(true, true);
	}

	private static NhAimTrainerConfig config(boolean enabled, boolean pianoTilesEnabled)
	{
		return config(enabled, pianoTilesEnabled, NhAimTrainerConfig.GameTargetShape.SQUARES, 36);
	}

	private static NhAimTrainerConfig config(
		boolean enabled,
		boolean pianoTilesEnabled,
		NhAimTrainerConfig.GameTargetShape gameTargetShape,
		int gameTargetAverageSize)
	{
		return config(enabled, pianoTilesEnabled, gameTargetShape, gameTargetAverageSize, true, 35);
	}

	private static NhAimTrainerConfig config(
		boolean enabled,
		boolean pianoTilesEnabled,
		NhAimTrainerConfig.GameTargetShape gameTargetShape,
		int gameTargetAverageSize,
		boolean pianoTileAudioEnabled,
		int pianoTileAudioVolume)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			if ("enabled".equals(method.getName()))
			{
				return enabled;
			}
			if ("pianoTilesEnabled".equals(method.getName()))
			{
				return pianoTilesEnabled;
			}
			if ("boxOpacity".equals(method.getName()))
			{
				return 86;
			}
			if ("pianoTileScores".equals(method.getName()))
			{
				return "";
			}
			if ("pianoTileAudioEnabled".equals(method.getName()))
			{
				return pianoTileAudioEnabled;
			}
			if ("pianoTileAudioVolume".equals(method.getName()))
			{
				return pianoTileAudioVolume;
			}
			if ("gameTargetShape".equals(method.getName()))
			{
				return gameTargetShape;
			}
			if ("gameTargetAverageSize".equals(method.getName()))
			{
				return gameTargetAverageSize;
			}
			return defaultValue(method.getReturnType());
		};
		return (NhAimTrainerConfig) Proxy.newProxyInstance(
			NhAimTrainerConfig.class.getClassLoader(),
			new Class<?>[]{NhAimTrainerConfig.class},
			handler);
	}

	private static int maxAbsPcm16(byte[] pcm)
	{
		int max = 0;
		for (int index = 0; index + 1 < pcm.length; index += 2)
		{
			int low = pcm[index] & 0xff;
			int high = pcm[index + 1];
			int value = (high << 8) | low;
			max = Math.max(max, Math.abs(value));
		}
		return max;
	}

	private static Overlay overlay(NhAimTrainerPlugin plugin) throws Exception
	{
		return (Overlay) getField(plugin, "overlay");
	}

	@SuppressWarnings("unchecked")
	private static List<Object> aimBoxList(NhAimTrainerPlugin plugin, String fieldName) throws Exception
	{
		return (List<Object>) getField(plugin, fieldName);
	}

	private static Object inventorySlotArray(int slotIndex, int itemId, Rectangle bounds) throws Exception
	{
		Class<?> slotClass = Class.forName("com.nhtrainer.inputtweaks.NhAimTrainerPlugin$InventorySlot");
		Constructor<?> constructor = slotClass.getDeclaredConstructor(int.class, int.class, Rectangle.class);
		constructor.setAccessible(true);
		Object slots = Array.newInstance(slotClass, 1);
		Array.set(slots, 0, constructor.newInstance(slotIndex, itemId, bounds));
		return slots;
	}

	private static Object inventoryAimBox(int slotIndex, int itemId, Rectangle bounds) throws Exception
	{
		Class<?> boxClass = Class.forName("com.nhtrainer.inputtweaks.NhAimTrainerPlugin$InventoryAimBox");
		Constructor<?> constructor = boxClass.getDeclaredConstructor(int.class, int.class, Rectangle.class);
		constructor.setAccessible(true);
		return constructor.newInstance(slotIndex, itemId, bounds);
	}

	private static Object widgetAimBox(int widgetId, Rectangle bounds) throws Exception
	{
		Class<?> boxClass = Class.forName("com.nhtrainer.inputtweaks.NhAimTrainerPlugin$WidgetAimBox");
		Constructor<?> constructor = boxClass.getDeclaredConstructor(int.class, Rectangle.class);
		constructor.setAccessible(true);
		return constructor.newInstance(widgetId, bounds);
	}

	private static Object gameAimBox(Rectangle bounds) throws Exception
	{
		Class<?> boxClass = Class.forName("com.nhtrainer.inputtweaks.NhAimTrainerPlugin$GameAimBox");
		Constructor<?> constructor = boxClass.getDeclaredConstructor(Rectangle.class, long.class);
		constructor.setAccessible(true);
		return constructor.newInstance(bounds, System.nanoTime());
	}

	private static Object pianoTile(int lane, double y) throws Exception
	{
		Class<?> tileClass = Class.forName("com.nhtrainer.inputtweaks.NhAimTrainerPlugin$PianoTile");
		Constructor<?> constructor = tileClass.getDeclaredConstructor(int.class, double.class);
		constructor.setAccessible(true);
		return constructor.newInstance(lane, y);
	}

	private static Widget inventoryWidget(Widget[] children)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "isHidden":
					return false;
				case "getChild":
					int slot = (Integer) args[0];
					return slot >= 0 && slot < children.length ? children[slot] : null;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			handler);
	}

	private static Widget inventoryItemWidget(int index, int itemId, Rectangle bounds)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "isHidden":
					return false;
				case "getIndex":
					return index;
				case "getItemId":
					return itemId;
				case "getBounds":
					return new Rectangle(bounds);
				case "getWidth":
					return bounds.width;
				case "getHeight":
					return bounds.height;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			handler);
	}

	private static Widget widget(int widgetId, Rectangle bounds)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "isHidden":
					return false;
				case "getId":
					return widgetId;
				case "getBounds":
					return new Rectangle(bounds);
				case "getWidth":
					return bounds.width;
				case "getHeight":
					return bounds.height;
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Widget) Proxy.newProxyInstance(
			Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class},
			handler);
	}

	private static MenuOptionClicked menuEvent(MenuAction action, boolean itemOp)
	{
		return menuEvent(action, itemOp, -1);
	}

	private static MenuOptionClicked inventoryMenuEvent(MenuAction action, boolean itemOp)
	{
		return menuEvent(action, itemOp, InterfaceID.Inventory.ITEMS);
	}

	private static MenuOptionClicked menuEvent(MenuAction action, boolean itemOp, int param1)
	{
		return new MenuOptionClicked(menuEntry(action, itemOp, param1, -1, itemOp ? "Use" : "Aim box"));
	}

	private static MenuEntry menuEntry(MenuAction action, boolean itemOp, int param1, int itemId, String option)
	{
		return menuEntry(action, itemOp, param1, itemId, option, -1);
	}

	private static MenuEntry menuEntry(MenuAction action, boolean itemOp, int param1, int itemId, String option, int param0)
	{
		return menuEntry(action, itemOp, param1, itemId, option, param0, null);
	}

	private static MenuEntry menuEntry(MenuAction action, boolean itemOp, int param1, int itemId, String option, int param0, Actor actor)
	{
		return menuEntry(action, itemOp, param1, itemId, option, param0, actor, null);
	}

	private static MenuEntry menuEntry(
		MenuAction action,
		boolean itemOp,
		int param1,
		int itemId,
		String option,
		int param0,
		Actor actor,
		Consumer<MenuEntry> onClick)
	{
		InvocationHandler handler = (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getOption":
					return option;
				case "getType":
					return action;
				case "isItemOp":
					return itemOp;
				case "getParam1":
					return param1;
				case "getParam0":
					return param0;
				case "getIdentifier":
					return -1;
				case "getItemId":
					return itemId;
				case "getWidget":
					return null;
				case "getActor":
					return actor;
				case "getNpc":
				case "getPlayer":
					return null;
				case "onClick":
					if (args != null && args.length == 1)
					{
						return proxy;
					}
					return onClick;
				case "getTarget":
					return "";
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			handler);
	}

	private static MutableActor mutableActor(Shape shape)
	{
		return new MutableActor(shape);
	}

	private static final class ImmediateClientThread extends ClientThread
	{
		@Override
		public void invoke(Runnable runnable)
		{
			runnable.run();
		}
	}

	private static final class CapturingMenuEntry implements InvocationHandler
	{
		private final MenuEntry proxy = (MenuEntry) Proxy.newProxyInstance(
			MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			this);
		private String option;
		private String target = "";
		private MenuAction type;
		private int identifier = -1;
		private int param0 = -1;
		private int param1 = -1;
		private int itemId = -1;
		private int setOptionCalls;
		private Consumer<MenuEntry> onClick;

		private MenuEntry proxy()
		{
			return proxy;
		}

		@Override
		public Object invoke(Object proxyObject, Method method, Object[] args)
		{
			switch (method.getName())
			{
				case "setOption":
					option = (String) args[0];
					setOptionCalls++;
					return proxy;
				case "getOption":
					return option;
				case "setTarget":
					target = (String) args[0];
					return proxy;
				case "getTarget":
					return target;
				case "setType":
					type = (MenuAction) args[0];
					return proxy;
				case "getType":
					return type;
				case "setIdentifier":
					identifier = (Integer) args[0];
					return proxy;
				case "getIdentifier":
					return identifier;
				case "setParam0":
					param0 = (Integer) args[0];
					return proxy;
				case "getParam0":
					return param0;
				case "setParam1":
					param1 = (Integer) args[0];
					return proxy;
				case "getParam1":
					return param1;
				case "setItemId":
					itemId = (Integer) args[0];
					return proxy;
				case "getItemId":
					return itemId;
				case "onClick":
					if (args != null && args.length == 1)
					{
						@SuppressWarnings("unchecked")
						Consumer<MenuEntry> callback = (Consumer<MenuEntry>) args[0];
						onClick = callback;
						return proxy;
					}
					return onClick;
				default:
					return defaultValue(method.getReturnType());
			}
		}
	}

	private static final class MutableActor implements InvocationHandler
	{
		private final Actor proxy = (Actor) Proxy.newProxyInstance(
			Actor.class.getClassLoader(),
			new Class<?>[]{Actor.class},
			this);
		private Shape shape;

		private MutableActor(Shape shape)
		{
			this.shape = shape;
		}

		@Override
		public Object invoke(Object proxyObject, Method method, Object[] args)
		{
			if ("getConvexHull".equals(method.getName()))
			{
				return shape;
			}
			return defaultValue(method.getReturnType());
		}
	}

	private static Object defaultValue(Class<?> returnType)
	{
		if (returnType == boolean.class)
		{
			return false;
		}
		if (returnType == int.class)
		{
			return 0;
		}
		if (returnType == long.class)
		{
			return 0L;
		}
		return null;
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Field field = NhAimTrainerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object getField(Object target, String name) throws Exception
	{
		Field field = NhAimTrainerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Object getNestedField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setNestedField(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static int renderedAlphaAt(NhAimTrainerPlugin plugin, int x, int y) throws Exception
	{
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay(plugin).render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		return (image.getRGB(x, y) >>> 24) & 0xff;
	}
}
