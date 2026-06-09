package com.nhtrainer.inputtweaks;

import com.google.inject.Provides;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

@Slf4j
@PluginDescriptor(
	name = "NH Input Tweaks",
	description = "Keeps NH input feel tweaks active.",
	tags = {"nh", "input", "inventory", "tabs"},
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
	private static final int HOTKEY_CAN_CLOSE_SIDE_PANEL = 1;
	private static final int HOTKEY_CANNOT_CLOSE_SIDE_PANEL = 0;
	private static final long INVENTORY_FEEDBACK_PRESS_NANOS = TimeUnit.MILLISECONDS.toNanos(140L);
	private static final long INVENTORY_FEEDBACK_RELEASE_NANOS = TimeUnit.MILLISECONDS.toNanos(80L);
	private static final int INVENTORY_FEEDBACK_DRAG_THRESHOLD = 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private NhInputTweaksConfig config;

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

	private final MouseListener inventoryFeedbackMouseListener = new net.runelite.client.input.MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent mouseEvent)
		{
			handleInventoryFeedbackMousePressed(mouseEvent);
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent mouseEvent)
		{
			handleInventoryFeedbackMouseReleased(mouseEvent);
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent mouseEvent)
		{
			handleInventoryFeedbackMouseDragged(mouseEvent);
			return mouseEvent;
		}
	};

	private final InventoryFeedbackOverlay inventoryFeedbackOverlay = new InventoryFeedbackOverlay();
	private final Map<Long, BufferedImage> inventoryFeedbackDarkImageCache = new HashMap<>();
	private volatile InventoryFeedbackSlot[] inventoryFeedbackSlots = new InventoryFeedbackSlot[0];
	private volatile int inventoryFeedbackWidgetId = -1;
	private volatile int inventoryFeedbackSlotIndex = -1;
	private volatile int inventoryFeedbackItemId = -1;
	private volatile int inventoryFeedbackSlotX = -1;
	private volatile int inventoryFeedbackSlotY = -1;
	private volatile int inventoryFeedbackSlotWidth = -1;
	private volatile int inventoryFeedbackSlotHeight = -1;
	private volatile long inventoryFeedbackUntilNanos = -1L;
	private volatile boolean inventoryFeedbackPressed;
	private volatile boolean inventoryFeedbackDragging;
	private volatile int inventoryFeedbackPressX = -1;
	private volatile int inventoryFeedbackPressY = -1;
	private NhInputTweaksPanel panel;
	private NavigationButton navigationButton;

	@Provides
	NhInputTweaksConfig provideConfig()
	{
		return configManager.getConfig(NhInputTweaksConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new NhInputTweaksPanel(configManager, config);
		navigationButton = NavigationButton.builder()
			.tooltip("NH Input Tweaks")
			.icon(createNavigationIcon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		keyManager.registerKeyListener(fastTabKeyListener);
		mouseManager.registerMouseListener(inventoryFeedbackMouseListener);
		overlayManager.add(inventoryFeedbackOverlay);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(fastTabKeyListener);
		mouseManager.unregisterMouseListener(inventoryFeedbackMouseListener);
		overlayManager.remove(inventoryFeedbackOverlay);
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}
		panel = null;
		clearInventoryFeedback();
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		refreshInventoryFeedbackSlots();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!NhInputTweaksConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (NhInputTweaksConfig.ITEM_DARKENING_AMOUNT.equals(event.getKey()))
		{
			inventoryFeedbackDarkImageCache.clear();
			repaintClientCanvas();
		}

		NhInputTweaksPanel currentPanel = panel;
		if (currentPanel != null)
		{
			SwingUtilities.invokeLater(currentPanel::refresh);
		}
	}

	private void handleFastTabKeyPressed(KeyEvent keyEvent)
	{
		int keyCode = keyEvent.getKeyCode();
		if (!config.fastTabsEnabled() || keyCode < KeyEvent.VK_F1 || keyCode > KeyEvent.VK_F12 || keyEvent.isConsumed())
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
			client.runScript(TOPLEVEL_KEYPRESS_SCRIPT_ID, jagexKeyCode, layoutKey, sidePanelCloseMode(layoutKey));
		}
		catch (Exception ex)
		{
			log.warn("Fast F-key tab script failed for key {} layout {}", jagexKeyCode, layoutKey, ex);
		}
	}

	private int sidePanelCloseMode(int layoutKey)
	{
		if (layoutKey == TOPLEVEL_RESIZABLE_LAYOUT_KEY
			&& client.getVarbitValue(VarbitID.HOTKEY_CANNOT_CLOSE_SIDEPANEL) == 1)
		{
			return HOTKEY_CANNOT_CLOSE_SIDE_PANEL;
		}

		return HOTKEY_CAN_CLOSE_SIDE_PANEL;
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

	private void refreshInventoryFeedbackSlots()
	{
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory == null || inventory.isHidden())
		{
			inventoryFeedbackSlots = new InventoryFeedbackSlot[0];
			return;
		}

		InventoryFeedbackSlot[] slots = new InventoryFeedbackSlot[28];
		int count = 0;
		for (int slot = 0; slot < 28; slot++)
		{
			Widget item = inventory.getChild(slot);
			if (item == null || item.isHidden() || item.getItemId() <= 0)
			{
				continue;
			}

			Rectangle bounds = item.getBounds();
			if (bounds != null && bounds.width > 0 && bounds.height > 0)
			{
				slots[count++] = new InventoryFeedbackSlot(
					item.getId(),
					item.getIndex(),
					item.getItemId(),
					bounds.x,
					bounds.y,
					bounds.width,
					bounds.height);
			}
		}
		inventoryFeedbackSlots = count == slots.length ? slots : Arrays.copyOf(slots, count);
	}

	private void handleInventoryFeedbackMousePressed(MouseEvent mouseEvent)
	{
		try
		{
			if (!isLeftMousePress(mouseEvent))
			{
				return;
			}

			if (config.itemDarkeningAmount() <= 0)
			{
				clearInventoryFeedback();
				return;
			}

			if (!isClientCanvasMouseEvent(mouseEvent))
			{
				clearInventoryFeedback();
				return;
			}

			InventoryFeedbackSlot slot = inventoryFeedbackSlotAt(mouseEvent.getX(), mouseEvent.getY());
			if (slot == null)
			{
				clearInventoryFeedback();
				return;
			}

			inventoryFeedbackWidgetId = slot.widgetId;
			inventoryFeedbackSlotIndex = slot.slotIndex;
			inventoryFeedbackItemId = slot.itemId;
			inventoryFeedbackSlotX = slot.x;
			inventoryFeedbackSlotY = slot.y;
			inventoryFeedbackSlotWidth = slot.width;
			inventoryFeedbackSlotHeight = slot.height;
			inventoryFeedbackPressX = mouseEvent.getX();
			inventoryFeedbackPressY = mouseEvent.getY();
			inventoryFeedbackDragging = false;
			inventoryFeedbackPressed = true;
			inventoryFeedbackUntilNanos = System.nanoTime() + INVENTORY_FEEDBACK_PRESS_NANOS;
			repaintClientCanvas();
		}
		catch (RuntimeException ex)
		{
			log.debug("Inventory feedback press failed", ex);
		}
	}

	private void handleInventoryFeedbackMouseReleased(MouseEvent mouseEvent)
	{
		try
		{
			if (!isLeftMousePress(mouseEvent) && mouseEvent.getButton() != MouseEvent.BUTTON1)
			{
				return;
			}

			if (inventoryFeedbackWidgetId < 0)
			{
				return;
			}

			inventoryFeedbackPressed = false;
			inventoryFeedbackDragging = false;
			inventoryFeedbackUntilNanos = System.nanoTime() + INVENTORY_FEEDBACK_RELEASE_NANOS;
			repaintClientCanvas();
		}
		catch (RuntimeException ex)
		{
			log.debug("Inventory feedback release failed", ex);
		}
	}

	private void handleInventoryFeedbackMouseDragged(MouseEvent mouseEvent)
	{
		try
		{
			if (!inventoryFeedbackPressed || inventoryFeedbackWidgetId < 0 || !isClientCanvasMouseEvent(mouseEvent))
			{
				return;
			}

			if (Math.abs(mouseEvent.getX() - inventoryFeedbackPressX) > INVENTORY_FEEDBACK_DRAG_THRESHOLD
				|| Math.abs(mouseEvent.getY() - inventoryFeedbackPressY) > INVENTORY_FEEDBACK_DRAG_THRESHOLD)
			{
				inventoryFeedbackDragging = true;
			}
			repaintClientCanvas();
		}
		catch (RuntimeException ex)
		{
			log.debug("Inventory feedback drag failed", ex);
		}
	}

	private void clearInventoryFeedback()
	{
		inventoryFeedbackWidgetId = -1;
		inventoryFeedbackSlotIndex = -1;
		inventoryFeedbackItemId = -1;
		inventoryFeedbackSlotX = -1;
		inventoryFeedbackSlotY = -1;
		inventoryFeedbackSlotWidth = -1;
		inventoryFeedbackSlotHeight = -1;
		inventoryFeedbackUntilNanos = -1L;
		inventoryFeedbackPressed = false;
		inventoryFeedbackDragging = false;
		repaintClientCanvas();
	}

	private InventoryFeedbackSlot inventoryFeedbackSlotAt(int x, int y)
	{
		for (InventoryFeedbackSlot slot : inventoryFeedbackSlots)
		{
			if (slot.contains(x, y))
			{
				return slot;
			}
		}
		return null;
	}

	private BufferedImage darkenedInventoryImage(int itemId, int quantity, int darkeningAmount)
	{
		int darkening = Math.max(0, Math.min(100, darkeningAmount));
		if (darkening <= 0)
		{
			return null;
		}

		long key = (((long) itemId) << 40) ^ (((long) quantity) << 8) ^ darkening;
		BufferedImage cached = inventoryFeedbackDarkImageCache.get(key);
		if (cached != null)
		{
			return cached;
		}

		BufferedImage source = itemManager.getImage(itemId, quantity, false);
		if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0)
		{
			return null;
		}

		int brightness = 100 - darkening;
		float multiplier = brightness / 100.0f;
		BufferedImage darkened = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		boolean hasVisiblePixels = false;
		for (int y = 0; y < source.getHeight(); y++)
		{
			for (int x = 0; x < source.getWidth(); x++)
			{
				int argb = source.getRGB(x, y);
				int alpha = argb >>> 24;
				if (alpha == 0)
				{
					continue;
				}

				hasVisiblePixels = true;
				int red = Math.round(((argb >>> 16) & 0xff) * multiplier);
				int green = Math.round(((argb >>> 8) & 0xff) * multiplier);
				int blue = Math.round((argb & 0xff) * multiplier);
				darkened.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
			}
		}

		if (!hasVisiblePixels)
		{
			return null;
		}

		inventoryFeedbackDarkImageCache.put(key, darkened);
		return darkened;
	}

	private boolean isInventoryFeedbackWidgetItem(WidgetItem widgetItem)
	{
		Widget widget = widgetItem.getWidget();
		int slotIndex = inventoryFeedbackSlotIndex;
		if (widget != null && slotIndex >= 0 && widget.getId() == inventoryFeedbackWidgetId && widget.getIndex() == slotIndex)
		{
			return true;
		}

		Rectangle originalBounds = widgetItem.getCanvasBounds(false);
		return originalBounds != null
			&& boundsMatch(originalBounds, inventoryFeedbackSlotX, inventoryFeedbackSlotY, inventoryFeedbackSlotWidth, inventoryFeedbackSlotHeight);
	}

	private boolean boundsMatch(Rectangle bounds, int x, int y, int width, int height)
	{
		if (x < 0 || y < 0 || width <= 0 || height <= 0)
		{
			return false;
		}

		return Math.abs(bounds.x - x) <= 1
			&& Math.abs(bounds.y - y) <= 1
			&& Math.abs(bounds.width - width) <= 1
			&& Math.abs(bounds.height - height) <= 1;
	}

	private boolean isClientCanvasMouseEvent(MouseEvent mouseEvent)
	{
		return mouseEvent != null && client != null && mouseEvent.getSource() == client.getCanvas();
	}

	private boolean isLeftMousePress(MouseEvent mouseEvent)
	{
		return mouseEvent != null && (SwingUtilities.isLeftMouseButton(mouseEvent) || mouseEvent.getButton() == MouseEvent.BUTTON1);
	}

	private void repaintClientCanvas()
	{
		if (client != null && client.getCanvas() != null)
		{
			client.getCanvas().repaint();
		}
	}

	private final class InventoryFeedbackOverlay extends WidgetItemOverlay
	{
		private InventoryFeedbackOverlay()
		{
			showOnInventory();
		}

		@Override
		public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
		{
			if (inventoryFeedbackWidgetId < 0 || widgetItem == null || itemId != inventoryFeedbackItemId)
			{
				return;
			}

			if (!isInventoryFeedbackWidgetItem(widgetItem))
			{
				return;
			}

			long untilNanos = inventoryFeedbackUntilNanos;
			if (untilNanos <= 0)
			{
				return;
			}

			long now = System.nanoTime();
			boolean pressed = inventoryFeedbackPressed;
			long remaining = untilNanos - now;
			if (!pressed && remaining <= 0)
			{
				return;
			}

			float fade = pressed ? 1.0f : Math.min(1.0f, remaining / (float) INVENTORY_FEEDBACK_RELEASE_NANOS);
			if (fade <= 0)
			{
				return;
			}

			BufferedImage darkened = darkenedInventoryImage(itemId, widgetItem.getQuantity(), config.itemDarkeningAmount());
			if (darkened == null)
			{
				return;
			}

			Rectangle bounds = widgetItem.getCanvasBounds();
			Composite previousComposite = graphics.getComposite();
			graphics.setComposite(AlphaComposite.SrcOver.derive(fade));
			graphics.drawImage(darkened, bounds.x, bounds.y, null);
			graphics.setComposite(previousComposite);
			repaintClientCanvas();
		}
	}

	private BufferedImage createNavigationIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(new Color(28, 28, 28, 230));
			graphics.fillRoundRect(1, 1, 14, 14, 4, 4);
			graphics.setColor(ColorScheme.BRAND_ORANGE);
			graphics.drawRoundRect(1, 1, 14, 14, 4, 4);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
			graphics.drawString("F", 5, 11);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static final class InventoryFeedbackSlot
	{
		private final int widgetId;
		private final int slotIndex;
		private final int itemId;
		private final int x;
		private final int y;
		private final int width;
		private final int height;

		private InventoryFeedbackSlot(int widgetId, int slotIndex, int itemId, int x, int y, int width, int height)
		{
			this.widgetId = widgetId;
			this.slotIndex = slotIndex;
			this.itemId = itemId;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		private boolean contains(int pointX, int pointY)
		{
			return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
		}
	}
}
