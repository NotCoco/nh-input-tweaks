package com.nhtrainer.inputtweaks;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

@PluginDescriptor(
	name = "Aim Trainer",
	description = "Adds visual click-box targets for inventory, spellbook, and viewport aim training.",
	tags = {"nh", "aim", "trainer", "pvp"},
	enabledByDefault = true
)
public class NhAimTrainerPlugin extends Plugin
{
	private static final String AIM_BOX_OPTION = "Aim box";
	private static final int INVENTORY_GROUP = InterfaceID.Inventory.ITEMS >>> 16;
	private static final int SPELLBOOK_GROUP = InterfaceID.MagicSpellbook.UNIVERSE >>> 16;
	private static final long GAME_TARGET_FADE_NANOS = 1_000_000_000L;
	private static final int GAME_TARGET_COUNT = 2;
	private static final int GAME_TARGET_MIN_SIZE = 14;
	private static final int GAME_TARGET_MAX_SIZE = 96;
	private static final int INVENTORY_DRAG_THRESHOLD = 3;
	private static final int MAX_INVENTORY_SLOT_DIMENSION = 96;
	private static final long OSRS_GAME_TICK_NANOS = 600_000_000L;
	private static final int PIANO_TILE_LANES = 4;
	private static final int PIANO_MAX_ACTIVE_TILES = 4;
	private static final int PIANO_MAX_LOCAL_SCORES = 20;
	private static final long PIANO_BASE_SPAWN_NANOS = 540_000_000L;
	private static final long PIANO_MIN_SPAWN_NANOS = 240_000_000L;
	private static final double PIANO_BASE_PIXELS_PER_SECOND = 330.0;
	private static final double PIANO_SCORE_SPEED_STEP = 4.0;
	private static final double PIANO_PROGRESS_MULTIPLIER = 1.2;
	private static final int PIANO_STARTING_DIFFICULTY_SCORE = 15;
	private static final int PIANO_SOUND_SAMPLE_RATE = 44_100;
	private static final double PIANO_SOUND_SECONDS = 0.115;
	private static final double PIANO_SOUND_MAX_GAIN = 0.18;
	private static final double PIANO_SHEPARD_ROOT_HZ = 261.625565;
	private static final double PIANO_SHEPARD_CENTER_HZ = 740.0;
	private static final double PIANO_SHEPARD_SPREAD_OCTAVES = 1.15;
	private static final int PIANO_SHEPARD_LOW_OCTAVE = -1;
	private static final int PIANO_SHEPARD_HIGH_OCTAVE = 5;
	private static final DateTimeFormatter SCORE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());

	@Inject
	private Client client;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private AudioPlayer audioPlayer;

	@Inject
	private NhAimTrainerConfig config;

	private final Random random = new Random();
	private final AimTrainerOverlay overlay = new AimTrainerOverlay();
	private final List<InventoryAimBox> inventoryBoxes = new CopyOnWriteArrayList<>();
	private final List<WidgetAimBox> spellBoxes = new CopyOnWriteArrayList<>();
	private final List<GameAimBox> gameBoxes = new CopyOnWriteArrayList<>();
	private final List<ActorAimBox> actorBoxes = new CopyOnWriteArrayList<>();
	private final List<PianoTile> pianoTiles = new CopyOnWriteArrayList<>();
	private final Set<String> offeredAimBoxTargets = new HashSet<>();
	private static volatile NhAimTrainerPlugin activeInstance;
	private volatile InventorySlot[] inventorySlotSnapshot = new InventorySlot[0];
	private int clientFrame;
	private boolean consumeTrainingRelease;
	private boolean consumeTrainingClick;
	private boolean markedInventoryAimPressActive;
	private boolean inventoryDirectPressActive;
	private boolean inventoryDirectPressDragged;
	private int inventoryDirectPressX = -1;
	private int inventoryDirectPressY = -1;
	private int pendingInventoryActionBlocks;
	private long pendingInventoryActionBlockUntilNanos = -1L;
	private long directInventoryFallbackBlockUntilNanos = -1L;
	private long directMarkedSpellBlockUntilNanos = -1L;
	private long directOverlayBlockUntilNanos = -1L;
	private long directGameWorldBlockUntilNanos = -1L;
	private boolean menuOpen;
	private int lastMenuOpenFrame = -1;
	private int lastMenuX;
	private int lastMenuY;
	private int lastMenuWidth;
	private int lastMenuHeight;
	private boolean pianoRunning;
	private boolean pianoGameOver;
	private int pianoScore;
	private int lastPianoScore;
	private long lastPianoEndMillis = -1L;
	private long lastPianoUpdateNanos = -1L;
	private long nextPianoSpawnNanos = -1L;
	private int lastPianoLane = -1;
	private int offeredAimBoxFrame = -1;
	private ExecutorService pianoSoundExecutor;
	private NhAimTrainerPanel panel;
	private NavigationButton navigationButton;

	private final MouseListener mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent mouseEvent)
		{
			trackInventoryDirectPress(mouseEvent);
			handleTrainingMousePress(mouseEvent);
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent mouseEvent)
		{
			trackInventoryDirectDrag(mouseEvent);
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent mouseEvent)
		{
			trackInventoryDirectRelease(mouseEvent);
			if (consumeTrainingRelease && isLeftMouse(mouseEvent))
			{
				consumeTrainingRelease = false;
				markedInventoryAimPressActive = false;
				mouseEvent.consume();
			}
			return mouseEvent;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent mouseEvent)
		{
			if (consumeTrainingClick && isLeftMouse(mouseEvent))
			{
				consumeTrainingClick = false;
				markedInventoryAimPressActive = false;
				mouseEvent.consume();
			}
			return mouseEvent;
		}
	};

	@Provides
	NhAimTrainerConfig provideConfig()
	{
		return configManager.getConfig(NhAimTrainerConfig.class);
	}

	@Override
	protected void startUp()
	{
		activeInstance = this;
		panel = new NhAimTrainerPanel(
			configManager,
			config,
			this::resetAimBoxes,
			this::resetPianoTiles,
			this::saveLocalPianoScore,
			this::pianoStatusText,
			this::pianoLeaderboardText);
		navigationButton = NavigationButton.builder()
			.tooltip("Aim Trainer")
			.icon(createNavigationIcon())
			.priority(9)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		mouseManager.registerMouseListener(mouseListener);
		overlayManager.add(overlay);
		pianoSoundExecutor = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "nh-aim-trainer-piano-audio");
			thread.setDaemon(true);
			return thread;
		});
		clientThread.invoke(this::refreshClientThreadState);
	}

	@Override
	protected void shutDown()
	{
		if (activeInstance == this)
		{
			activeInstance = null;
		}
		mouseManager.unregisterMouseListener(mouseListener);
		overlayManager.remove(overlay);
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}
		shutdownPianoSoundExecutor();
		panel = null;
		clearAimBoxState();
		pianoTiles.clear();
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		clientFrame++;
		refreshInventorySlotSnapshot();
		refreshAimBoxBounds();
		recordOpenMenuBounds();
		clearStaleDirectClickState();
		if (config.enabled())
		{
			if (isPianoModeActive())
			{
				updatePianoTiles();
			}
			else
			{
				ensureGameTargets();
			}
			repaintClientCanvas();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!NhAimTrainerConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
		if (!config.enabled())
		{
			clearTransientInputState();
		}
		boolean modeChanged = NhAimTrainerConfig.ENABLED.equals(event.getKey())
			|| NhAimTrainerConfig.PIANO_TILES_ENABLED.equals(event.getKey());
		if (modeChanged && config.enabled() && config.pianoTilesEnabled())
		{
			clientThread.invoke(this::resetPianoTilesOnClientThread);
		}
		boolean gameTargetChanged = NhAimTrainerConfig.GAME_TARGET_SHAPE.equals(event.getKey())
			|| NhAimTrainerConfig.GAME_TARGET_AVERAGE_SIZE.equals(event.getKey());
		if (gameTargetChanged && config.enabled() && clientThread != null)
		{
			clientThread.invoke(() ->
			{
				gameBoxes.clear();
				if (!config.pianoTilesEnabled())
				{
					ensureGameTargets();
				}
				repaintClientCanvas();
			});
		}
		repaintClientCanvas();
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		recordOpenMenuBounds();
		clearDirectActionBlocksForMenuInteraction();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.enabled() || config.pianoTilesEnabled())
		{
			return;
		}

		MenuEntry source = event.getMenuEntry();
		if (!shouldOfferAimBox(source) || aimBoxEntryAlreadyPresent(source) || aimBoxOfferAlreadyQueued(source))
		{
			return;
		}

		boolean inventoryEntry = isInventoryEntry(source);
		boolean spellEntry = isSpellEntry(source);
		boolean actorEntry = isActorEntry(source);
		int slotIndex = source.getParam0();
		int itemId = source.getItemId();
		int widgetId = widgetIdForEntry(source);
		Actor actor = actorForEntry(source);
		client.createMenuEntry(-1)
			.setOption(AIM_BOX_OPTION)
			.setTarget(source.getTarget())
			.setType(MenuAction.RUNELITE)
			.setIdentifier(source.getIdentifier())
			.setParam0(source.getParam0())
			.setParam1(source.getParam1())
			.setItemId(source.getItemId())
			.onClick(entry -> clientThread.invoke(() ->
			{
				if (actorEntry)
				{
					toggleActorAimBox(actor);
				}
				else
				{
					toggleAimBox(inventoryEntry, spellEntry, slotIndex, itemId, widgetId);
				}
			}));
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.enabled())
		{
			return;
		}

		if (isAimBoxMenuAction(event))
		{
			if (config.pianoTilesEnabled())
			{
				return;
			}

			MenuEntry entry = event.getMenuEntry();
			if (entry != null && entry.onClick() != null)
			{
				return;
			}

			boolean inventoryEntry = isInventoryEntry(entry);
			boolean spellEntry = isSpellEntry(entry);
			boolean actorEntry = isActorEntry(entry);
			if (!inventoryEntry && !spellEntry && !actorEntry)
			{
				return;
			}

			event.consume();
			int slotIndex = event.getParam0();
			int itemId = event.getItemId();
			int widgetId = widgetIdForEntry(entry);
			Actor actor = actorForEntry(entry);
			clientThread.invoke(() ->
			{
				if (actorEntry)
				{
					toggleActorAimBox(actor);
				}
				else
				{
					toggleAimBox(inventoryEntry, spellEntry, slotIndex, itemId, widgetId);
				}
			});
			return;
		}

		boolean blockInventoryAction = isInventoryAction(event);
		boolean blockInventoryFallbackAction = isInventoryFallbackAction(event);
		boolean blockOverlayAction = isOverlayClickAction(event);
		boolean blockGameWorldAction = isGameWorldClickAction(event);
		if (blockOverlayAction || blockGameWorldAction || blockInventoryAction || blockInventoryFallbackAction || isMarkedSpellAction(event))
		{
			event.consume();
			if (blockInventoryAction || blockInventoryFallbackAction)
			{
				directInventoryFallbackBlockUntilNanos = -1L;
				if (blockInventoryFallbackAction)
				{
					clearPendingInventoryActionBlocks();
				}
			}
			if (blockOverlayAction)
			{
				directOverlayBlockUntilNanos = -1L;
			}
			if (blockGameWorldAction)
			{
				directGameWorldBlockUntilNanos = -1L;
			}
		}
	}

	private boolean isAimBoxMenuAction(MenuOptionClicked event)
	{
		return event.getMenuAction() == MenuAction.RUNELITE
			&& AIM_BOX_OPTION.equals(event.getMenuOption());
	}

	void resetAimBoxes()
	{
		clientThread.invoke(this::resetAimBoxesOnClientThread);
	}

	private void resetAimBoxesOnClientThread()
	{
		clearAimBoxState();
		if (config != null && config.enabled())
		{
			ensureGameTargets();
		}
		repaintClientCanvas();
	}

	private void clearAimBoxState()
	{
		inventoryBoxes.clear();
		spellBoxes.clear();
		gameBoxes.clear();
		actorBoxes.clear();
		offeredAimBoxTargets.clear();
		offeredAimBoxFrame = -1;
		inventorySlotSnapshot = new InventorySlot[0];
		clearTransientInputState();
		clearMenuBounds();
		repaintClientCanvas();
	}

	static boolean isInventoryAimBoxClick(Client client, int x, int y)
	{
		NhAimTrainerPlugin instance = activeInstance;
		return instance != null
			&& instance.client == client
			&& instance.config != null
			&& instance.config.enabled()
			&& !instance.config.pianoTilesEnabled()
			&& instance.inventoryAimBoxBoundsAtCached(x, y) != null;
	}

	private void handleTrainingMousePress(MouseEvent mouseEvent)
	{
		markedInventoryAimPressActive = false;
		if (!config.enabled() || !isLeftMouse(mouseEvent) || !isClientCanvasMouseEvent(mouseEvent) || isMenuInteraction(mouseEvent))
		{
			consumeTrainingRelease = false;
			consumeTrainingClick = false;
			return;
		}

		if (isPianoModeActive())
		{
			handlePianoMousePress(mouseEvent);
			return;
		}

		AimHit hit = aimHitAtCached(mouseEvent.getX(), mouseEvent.getY());
		if (hit == null)
		{
			if (inactivePanelAimBoxAtCached(mouseEvent.getX(), mouseEvent.getY()))
			{
				mouseEvent.consume();
				consumeTrainingRelease = true;
				consumeTrainingClick = true;
				directOverlayBlockUntilNanos = actionBlockUntilNanos();
				return;
			}

			if (isGameViewportClick(mouseEvent.getX(), mouseEvent.getY()))
			{
				mouseEvent.consume();
				consumeTrainingRelease = true;
				consumeTrainingClick = true;
				directGameWorldBlockUntilNanos = actionBlockUntilNanos();
				return;
			}

			consumeTrainingRelease = false;
			consumeTrainingClick = false;
			return;
		}

		clickAimHit(hit);
		if (hit.type == AimHitType.INVENTORY)
		{
			markedInventoryAimPressActive = true;
		}
		mouseEvent.consume();
		consumeTrainingRelease = true;
		consumeTrainingClick = true;
		repaintClientCanvas();
	}

	private void clickAimHit(AimHit hit)
	{
		switch (hit.type)
		{
			case INVENTORY:
				hit.inventoryBox.hiddenUntilNanos = feedbackHiddenUntilNanos();
				directInventoryFallbackBlockUntilNanos = actionBlockUntilNanos();
				return;
			case SPELL:
				hit.widgetBox.hiddenUntilNanos = feedbackHiddenUntilNanos();
				directMarkedSpellBlockUntilNanos = actionBlockUntilNanos();
				directOverlayBlockUntilNanos = actionBlockUntilNanos();
				return;
			case GAME:
				respawnGameTarget(hit.gameBox);
				directOverlayBlockUntilNanos = actionBlockUntilNanos();
				return;
			case ACTOR:
				hit.actorBox.hiddenUntilNanos = feedbackHiddenUntilNanos();
				directOverlayBlockUntilNanos = actionBlockUntilNanos();
				directGameWorldBlockUntilNanos = actionBlockUntilNanos();
				return;
			default:
				return;
		}
	}

	private boolean isPianoModeActive()
	{
		return config != null && config.enabled() && config.pianoTilesEnabled();
	}

	private void handlePianoMousePress(MouseEvent mouseEvent)
	{
		mouseEvent.consume();
		consumeTrainingRelease = true;
		consumeTrainingClick = true;
		directOverlayBlockUntilNanos = actionBlockUntilNanos();
		directGameWorldBlockUntilNanos = actionBlockUntilNanos();

		if (!pianoRunning)
		{
			if (!pianoGameOver)
			{
				resetPianoTilesOnClientThread();
			}
			repaintClientCanvas();
			refreshPanelLater();
			return;
		}

		PianoTile tile = pianoTileAt(mouseEvent.getX(), mouseEvent.getY());
		if (tile == null)
		{
			endPianoRun();
			return;
		}

		pianoTiles.remove(tile);
		pianoScore++;
		playPianoTileClickSound();
		long now = System.nanoTime();
		nextPianoSpawnNanos = Math.min(nextPianoSpawnNanos, now + pianoSpawnIntervalNanos());
		ensurePianoTileBuffer(now);
		repaintClientCanvas();
		refreshPanelLater();
	}

	private void resetPianoTiles()
	{
		clientThread.invoke(this::resetPianoTilesOnClientThread);
	}

	private void resetPianoTilesOnClientThread()
	{
		pianoTiles.clear();
		pianoRunning = isPianoModeActive();
		pianoGameOver = false;
		pianoScore = 0;
		lastPianoUpdateNanos = System.nanoTime();
		nextPianoSpawnNanos = lastPianoUpdateNanos;
		lastPianoLane = -1;
		if (pianoRunning)
		{
			spawnPianoTile();
			nextPianoSpawnNanos = lastPianoUpdateNanos + pianoSpawnIntervalNanos();
		}
		repaintClientCanvas();
		refreshPanelLater();
	}

	private void updatePianoTiles()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			pianoTiles.clear();
			pianoRunning = false;
			lastPianoUpdateNanos = -1L;
			return;
		}

		if (!pianoRunning && !pianoGameOver)
		{
			resetPianoTilesOnClientThread();
			return;
		}

		if (!pianoRunning)
		{
			return;
		}

		long now = System.nanoTime();
		if (lastPianoUpdateNanos < 0)
		{
			lastPianoUpdateNanos = now;
		}
		double seconds = Math.max(0.0, (now - lastPianoUpdateNanos) / 1_000_000_000.0);
		lastPianoUpdateNanos = now;
		double distance = pianoPixelsPerSecond() * seconds;
		int bottom = client.getViewportYOffset() + client.getViewportHeight();
		for (PianoTile tile : pianoTiles)
		{
			tile.y += distance;
			if (tile.y >= bottom)
			{
				endPianoRun();
				return;
			}
		}

		if (now >= nextPianoSpawnNanos)
		{
			ensurePianoTileBuffer(now);
		}
	}

	private void ensurePianoTileBuffer(long now)
	{
		while (pianoRunning && pianoTiles.size() < PIANO_MAX_ACTIVE_TILES && now >= nextPianoSpawnNanos)
		{
			spawnPianoTile();
			nextPianoSpawnNanos += pianoSpawnIntervalNanos();
		}
	}

	private void spawnPianoTile()
	{
		Rectangle viewport = viewportBounds();
		if (viewport == null)
		{
			return;
		}

		int lane = random.nextInt(PIANO_TILE_LANES);
		if (PIANO_TILE_LANES > 1 && lane == lastPianoLane)
		{
			lane = (lane + 1 + random.nextInt(PIANO_TILE_LANES - 1)) % PIANO_TILE_LANES;
		}
		lastPianoLane = lane;
		int height = pianoTileHeight(viewport);
		double y = viewport.y - height;
		if (!pianoTiles.isEmpty())
		{
			double topMostY = pianoTiles.stream()
				.mapToDouble(tile -> tile.y)
				.min()
				.orElse(y);
			y = Math.min(y, topMostY - height - 10);
		}
		pianoTiles.add(new PianoTile(lane, y));
	}

	private void endPianoRun()
	{
		lastPianoScore = pianoScore;
		lastPianoEndMillis = System.currentTimeMillis();
		pianoRunning = false;
		pianoGameOver = true;
		pianoTiles.clear();
		repaintClientCanvas();
		refreshPanelLater();
	}

	private long pianoSpawnIntervalNanos()
	{
		double difficultyScore = pianoDifficultyScore();
		long reduced = PIANO_BASE_SPAWN_NANOS - (long) Math.min(360_000_000.0, difficultyScore * 9_000_000.0);
		return Math.max(PIANO_MIN_SPAWN_NANOS, reduced);
	}

	private double pianoPixelsPerSecond()
	{
		return PIANO_BASE_PIXELS_PER_SECOND + pianoDifficultyScore() * PIANO_SCORE_SPEED_STEP;
	}

	private double pianoDifficultyScore()
	{
		return PIANO_STARTING_DIFFICULTY_SCORE + pianoScore * PIANO_PROGRESS_MULTIPLIER;
	}

	private void playPianoTileClickSound()
	{
		double gain = pianoSoundGain();
		if (gain <= 0.0)
		{
			return;
		}

		ExecutorService executor = pianoSoundExecutor;
		if (executor == null || executor.isShutdown())
		{
			return;
		}

		int soundStep = Math.max(0, pianoScore - 1);
		try
		{
			executor.execute(() -> playPianoTileClickSound(soundStep, gain));
		}
		catch (RejectedExecutionException ignored)
		{
			// Plugin shutdown can race with a final click.
		}
	}

	private void playPianoTileClickSound(int soundStep, double gain)
	{
		AudioPlayer player = audioPlayer;
		if (player == null)
		{
			return;
		}

		byte[] wav = buildPianoTileClickWav(soundStep, gain);
		if (wav.length == 0)
		{
			return;
		}

		try
		{
			player.play(new ByteArrayInputStream(wav), 0.0f);
		}
		catch (Exception ignored)
		{
			// Audio is optional; unavailable output should not affect the trainer.
		}
	}

	private byte[] buildPianoTileClickWav(int soundStep, double gain)
	{
		byte[] pcm = buildPianoTileClickPcm(soundStep, gain);
		if (pcm.length == 0)
		{
			return pcm;
		}

		byte[] wav = new byte[44 + pcm.length];
		writeAscii(wav, 0, "RIFF");
		writeLittleEndianInt(wav, 4, 36 + pcm.length);
		writeAscii(wav, 8, "WAVE");
		writeAscii(wav, 12, "fmt ");
		writeLittleEndianInt(wav, 16, 16);
		writeLittleEndianShort(wav, 20, 1);
		writeLittleEndianShort(wav, 22, 1);
		writeLittleEndianInt(wav, 24, PIANO_SOUND_SAMPLE_RATE);
		writeLittleEndianInt(wav, 28, PIANO_SOUND_SAMPLE_RATE * 2);
		writeLittleEndianShort(wav, 32, 2);
		writeLittleEndianShort(wav, 34, 16);
		writeAscii(wav, 36, "data");
		writeLittleEndianInt(wav, 40, pcm.length);
		System.arraycopy(pcm, 0, wav, 44, pcm.length);
		return wav;
	}

	private byte[] buildPianoTileClickPcm(int soundStep, double gain)
	{
		double clampedGain = Math.max(0.0, Math.min(PIANO_SOUND_MAX_GAIN, gain));
		if (clampedGain <= 0.0)
		{
			return new byte[0];
		}

		int sampleCount = Math.max(1, (int) Math.round(PIANO_SOUND_SAMPLE_RATE * PIANO_SOUND_SECONDS));
		byte[] data = new byte[sampleCount * 2];
		double duration = sampleCount / (double) PIANO_SOUND_SAMPLE_RATE;
		for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++)
		{
			double t = sampleIndex / (double) PIANO_SOUND_SAMPLE_RATE;
			double progress = t / duration;
			double semitone = soundStep + progress * 0.85;
			double pitchClass = pianoShepardPitchClassHz(semitone);
			double envelope = pianoSoundEnvelope(progress);
			double sample = 0.0;
			double weightSum = 0.0;

			for (int octave = PIANO_SHEPARD_LOW_OCTAVE; octave <= PIANO_SHEPARD_HIGH_OCTAVE; octave++)
			{
				double frequency = pitchClass * Math.pow(2.0, octave);
				if (frequency < 40.0 || frequency > PIANO_SOUND_SAMPLE_RATE / 2.0 - 200.0)
				{
					continue;
				}

				double octaveDistance = log2(frequency / PIANO_SHEPARD_CENTER_HZ);
				double weight = Math.exp(-(octaveDistance * octaveDistance) / (2.0 * PIANO_SHEPARD_SPREAD_OCTAVES * PIANO_SHEPARD_SPREAD_OCTAVES));
				sample += Math.sin(2.0 * Math.PI * frequency * t) * weight;
				weightSum += weight;
			}

			if (weightSum > 0.0)
			{
				sample /= weightSum;
			}

			sample *= clampedGain * envelope;
			int pcm = (int) Math.round(Math.max(-1.0, Math.min(1.0, sample)) * 32767.0);
			int byteIndex = sampleIndex * 2;
			data[byteIndex] = (byte) (pcm & 0xff);
			data[byteIndex + 1] = (byte) ((pcm >>> 8) & 0xff);
		}
		return data;
	}

	private void writeAscii(byte[] data, int offset, String value)
	{
		for (int i = 0; i < value.length(); i++)
		{
			data[offset + i] = (byte) value.charAt(i);
		}
	}

	private void writeLittleEndianInt(byte[] data, int offset, int value)
	{
		data[offset] = (byte) (value & 0xff);
		data[offset + 1] = (byte) ((value >>> 8) & 0xff);
		data[offset + 2] = (byte) ((value >>> 16) & 0xff);
		data[offset + 3] = (byte) ((value >>> 24) & 0xff);
	}

	private void writeLittleEndianShort(byte[] data, int offset, int value)
	{
		data[offset] = (byte) (value & 0xff);
		data[offset + 1] = (byte) ((value >>> 8) & 0xff);
	}

	private double pianoSoundEnvelope(double progress)
	{
		double attack = Math.min(1.0, progress / 0.08);
		double decay = Math.pow(Math.max(0.0, 1.0 - progress), 1.7);
		return attack * decay;
	}

	private double pianoShepardPitchClassHz(double semitone)
	{
		double wrappedSemitone = semitone % 12.0;
		if (wrappedSemitone < 0.0)
		{
			wrappedSemitone += 12.0;
		}
		return PIANO_SHEPARD_ROOT_HZ * Math.pow(2.0, wrappedSemitone / 12.0);
	}

	private double pianoSoundGain()
	{
		if (config == null || !config.pianoTileAudioEnabled())
		{
			return 0.0;
		}

		int volume = Math.max(0, Math.min(100, config.pianoTileAudioVolume()));
		if (volume <= 0)
		{
			return 0.0;
		}

		double normalized = volume / 100.0;
		return PIANO_SOUND_MAX_GAIN * normalized * normalized;
	}

	private double log2(double value)
	{
		return Math.log(value) / Math.log(2.0);
	}

	private void shutdownPianoSoundExecutor()
	{
		ExecutorService executor = pianoSoundExecutor;
		pianoSoundExecutor = null;
		if (executor != null)
		{
			executor.shutdownNow();
		}
	}

	private int pianoTileHeight(Rectangle viewport)
	{
		return Math.max(116, Math.min(236, viewport.height * 2 / 5));
	}

	private PianoTile pianoTileAt(int x, int y)
	{
		for (PianoTile tile : pianoTiles)
		{
			if (contains(pianoTileBounds(tile), x, y))
			{
				return tile;
			}
		}
		return null;
	}

	private Rectangle pianoTileBounds(PianoTile tile)
	{
		Rectangle viewport = viewportBounds();
		if (viewport == null)
		{
			return null;
		}

		int laneWidth = Math.max(1, viewport.width / PIANO_TILE_LANES);
		int x = viewport.x + laneWidth * tile.lane + 2;
		int width = Math.max(8, laneWidth - 4);
		if (tile.lane == PIANO_TILE_LANES - 1)
		{
			width = Math.max(8, viewport.x + viewport.width - x - 2);
		}
		return new Rectangle(x, (int) Math.round(tile.y), width, pianoTileHeight(viewport));
	}

	private Rectangle viewportBounds()
	{
		int width = client.getViewportWidth();
		int height = client.getViewportHeight();
		if (width <= 0 || height <= 0)
		{
			return null;
		}
		return new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(), width, height);
	}

	private void saveLocalPianoScore()
	{
		int score = pianoGameOver ? lastPianoScore : pianoScore;
		if (score <= 0)
		{
			return;
		}

		List<PianoScore> scores = localPianoScores();
		long timestamp = pianoGameOver && lastPianoEndMillis > 0 ? lastPianoEndMillis : System.currentTimeMillis();
		scores.add(new PianoScore(score, timestamp));
		scores.sort(Comparator
			.comparingInt((PianoScore entry) -> entry.score)
			.reversed()
			.thenComparingLong(entry -> entry.timestampMillis));
		if (scores.size() > PIANO_MAX_LOCAL_SCORES)
		{
			scores = new ArrayList<>(scores.subList(0, PIANO_MAX_LOCAL_SCORES));
		}
		configManager.setConfiguration(
			NhAimTrainerConfig.GROUP,
			NhAimTrainerConfig.PIANO_TILE_SCORES,
			serializePianoScores(scores));
	}

	private String pianoStatusText()
	{
		if (config == null || !config.enabled())
		{
			return "Aim trainer disabled";
		}
		if (!config.pianoTilesEnabled())
		{
			return "Piano tiles off";
		}
		if (pianoRunning)
		{
			return "Score: " + pianoScore;
		}
		if (pianoGameOver)
		{
			return "Game over. Score: " + lastPianoScore;
		}
		return "Ready";
	}

	private String pianoLeaderboardText()
	{
		List<PianoScore> scores = localPianoScores();
		if (scores.isEmpty())
		{
			return "No local scores saved yet.";
		}

		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < scores.size(); index++)
		{
			PianoScore score = scores.get(index);
			builder.append(index + 1)
				.append(". ")
				.append(score.score)
				.append(" - ")
				.append(SCORE_DATE_FORMAT.format(Instant.ofEpochMilli(score.timestampMillis)))
				.append('\n');
		}
		return builder.toString();
	}

	private List<PianoScore> localPianoScores()
	{
		String raw = configManager == null ? null : configManager.getConfiguration(NhAimTrainerConfig.GROUP, NhAimTrainerConfig.PIANO_TILE_SCORES);
		List<PianoScore> scores = parsePianoScores(raw);
		scores.sort(Comparator
			.comparingInt((PianoScore entry) -> entry.score)
			.reversed()
			.thenComparingLong(entry -> entry.timestampMillis));
		return scores;
	}

	private List<PianoScore> parsePianoScores(String raw)
	{
		List<PianoScore> scores = new ArrayList<>();
		if (raw == null || raw.trim().isEmpty())
		{
			return scores;
		}

		String[] rows = raw.split("\\n");
		for (String row : rows)
		{
			String[] parts = row.split("\\|", 2);
			if (parts.length != 2)
			{
				continue;
			}
			try
			{
				int score = Integer.parseInt(parts[0]);
				long timestampMillis = Long.parseLong(parts[1]);
				if (score > 0 && timestampMillis > 0)
				{
					scores.add(new PianoScore(score, timestampMillis));
				}
			}
			catch (NumberFormatException ignored)
			{
				// Ignore corrupt local score rows.
			}
		}
		return scores;
	}

	private String serializePianoScores(List<PianoScore> scores)
	{
		StringBuilder builder = new StringBuilder();
		for (PianoScore score : scores)
		{
			if (builder.length() > 0)
			{
				builder.append('\n');
			}
			builder.append(score.score).append('|').append(score.timestampMillis);
		}
		return builder.toString();
	}

	private void refreshPanelLater()
	{
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	private AimHit aimHitAtCached(int x, int y)
	{
		for (InventoryAimBox box : inventoryBoxes)
		{
			Rectangle bounds = box.activeBounds;
			if (isDrawable(box.hiddenUntilNanos) && contains(bounds, x, y))
			{
				return AimHit.inventory(box);
			}
		}

		for (WidgetAimBox box : spellBoxes)
		{
			Rectangle bounds = box.activeBounds;
			if (isDrawable(box.hiddenUntilNanos) && contains(bounds, x, y))
			{
				return AimHit.spell(box);
			}
		}

		for (GameAimBox box : gameBoxes)
		{
			if (isDrawable(box.hiddenUntilNanos) && containsGameBox(box, x, y))
			{
				return AimHit.game(box);
			}
		}

		for (ActorAimBox box : actorBoxes)
		{
			Shape shape = box.activeShape;
			if (isDrawable(box.hiddenUntilNanos) && contains(shape, x, y))
			{
				return AimHit.actor(box);
			}
		}
		return null;
	}

	private InventoryAimBox inventoryAimBoxAtCached(int x, int y)
	{
		for (InventoryAimBox box : inventoryBoxes)
		{
			Rectangle bounds = box.activeBounds;
			if (isDrawable(box.hiddenUntilNanos) && contains(bounds, x, y))
			{
				return box;
			}
		}
		return null;
	}

	private InventoryAimBox inventoryAimBoxBoundsAtCached(int x, int y)
	{
		for (InventoryAimBox box : inventoryBoxes)
		{
			if (contains(box.activeBounds, x, y))
			{
				return box;
			}
		}
		return null;
	}

	private boolean inactivePanelAimBoxAtCached(int x, int y)
	{
		for (InventoryAimBox box : inventoryBoxes)
		{
			if (isDrawable(box.hiddenUntilNanos)
				&& contains(box.lastBounds, x, y)
				&& box.activeBounds == null)
			{
				return true;
			}
		}

		for (WidgetAimBox box : spellBoxes)
		{
			if (isDrawable(box.hiddenUntilNanos)
				&& contains(box.lastBounds, x, y)
				&& box.activeBounds == null)
			{
				return true;
			}
		}
		return false;
	}

	private void addAimBox(boolean inventoryEntry, boolean spellEntry, int slotIndex, int itemId, int widgetId)
	{
		if (!config.enabled())
		{
			return;
		}

		if (inventoryEntry)
		{
			addInventoryAimBox(slotIndex, itemId);
			return;
		}

		if (spellEntry)
		{
			addSpellAimBox(widgetId);
		}
	}

	private void toggleAimBox(boolean inventoryEntry, boolean spellEntry, int slotIndex, int itemId, int widgetId)
	{
		if (!config.enabled())
		{
			return;
		}

		if (inventoryEntry)
		{
			toggleInventoryAimBox(slotIndex, itemId);
			return;
		}

		if (spellEntry)
		{
			toggleSpellAimBox(widgetId);
		}
	}

	private void addActorAimBox(Actor actor)
	{
		if (!config.enabled() || actor == null)
		{
			return;
		}

		Shape shape = validActorShape(actor);
		if (shape == null)
		{
			return;
		}

		for (ActorAimBox existing : actorBoxes)
		{
			if (existing.actor == actor)
			{
				existing.lastShape = shape;
				existing.activeShape = shape;
				existing.hiddenUntilNanos = -1L;
				repaintClientCanvas();
				return;
			}
		}

		actorBoxes.add(new ActorAimBox(actor, shape));
		repaintClientCanvas();
	}

	private void toggleActorAimBox(Actor actor)
	{
		if (!config.enabled() || actor == null)
		{
			return;
		}

		for (ActorAimBox existing : actorBoxes)
		{
			if (existing.actor == actor)
			{
				actorBoxes.remove(existing);
				repaintClientCanvas();
				return;
			}
		}

		addActorAimBox(actor);
	}

	private void addInventoryAimBox(int slotIndex, int itemId)
	{
		if (slotIndex < 0 || slotIndex >= 28)
		{
			Point mousePosition = client.getMouseCanvasPosition();
			InventorySlot slot = mousePosition == null ? null : inventorySlotAtCached(mousePosition.getX(), mousePosition.getY());
			if (slot == null)
			{
				return;
			}
			slotIndex = slot.slotIndex;
			itemId = slot.itemId;
		}

		Widget widget = inventoryWidgetForSlot(slotIndex);
		if (widget == null || widget.getItemId() <= 0)
		{
			return;
		}

		int effectiveItemId = itemId > 0 ? itemId : widget.getItemId();
		Rectangle bounds = inventorySlotBounds(widget);
		if (bounds == null)
		{
			return;
		}

		for (InventoryAimBox existing : inventoryBoxes)
		{
			if (existing.slotIndex == slotIndex && existing.itemId == effectiveItemId)
			{
				existing.lastBounds = bounds;
				existing.activeBounds = bounds;
				existing.hiddenUntilNanos = -1L;
				repaintClientCanvas();
				return;
			}
		}

		inventoryBoxes.add(new InventoryAimBox(slotIndex, effectiveItemId, bounds));
		repaintClientCanvas();
	}

	private void toggleInventoryAimBox(int slotIndex, int itemId)
	{
		if (slotIndex < 0 || slotIndex >= 28)
		{
			Point mousePosition = client.getMouseCanvasPosition();
			InventorySlot slot = mousePosition == null ? null : inventorySlotAtCached(mousePosition.getX(), mousePosition.getY());
			if (slot == null)
			{
				return;
			}
			slotIndex = slot.slotIndex;
			itemId = slot.itemId;
		}

		Widget widget = inventoryWidgetForSlot(slotIndex);
		if (widget == null || widget.getItemId() <= 0)
		{
			return;
		}

		int effectiveItemId = itemId > 0 ? itemId : widget.getItemId();
		for (InventoryAimBox existing : inventoryBoxes)
		{
			if (existing.slotIndex == slotIndex && existing.itemId == effectiveItemId)
			{
				inventoryBoxes.remove(existing);
				repaintClientCanvas();
				return;
			}
		}

		addInventoryAimBox(slotIndex, effectiveItemId);
	}

	private void addSpellAimBox(int widgetId)
	{
		Widget widget = client.getWidget(widgetId);
		Rectangle bounds = validBounds(widget);
		if (bounds == null)
		{
			return;
		}

		for (WidgetAimBox existing : spellBoxes)
		{
			if (existing.widgetId == widgetId)
			{
				existing.lastBounds = bounds;
				existing.activeBounds = bounds;
				existing.hiddenUntilNanos = -1L;
				repaintClientCanvas();
				return;
			}
		}

		spellBoxes.add(new WidgetAimBox(widgetId, bounds));
		repaintClientCanvas();
	}

	private void toggleSpellAimBox(int widgetId)
	{
		for (WidgetAimBox existing : spellBoxes)
		{
			if (existing.widgetId == widgetId)
			{
				spellBoxes.remove(existing);
				repaintClientCanvas();
				return;
			}
		}

		addSpellAimBox(widgetId);
	}

	private boolean shouldOfferAimBox(MenuEntry entry)
	{
		if (entry == null || AIM_BOX_OPTION.equals(entry.getOption()))
		{
			return false;
		}

		return isInventoryEntry(entry) || isSpellEntry(entry) || isActorEntry(entry);
	}

	private boolean aimBoxOfferAlreadyQueued(MenuEntry source)
	{
		String targetKey = aimBoxTargetKey(source);
		if (targetKey == null)
		{
			return true;
		}

		if (offeredAimBoxFrame != clientFrame)
		{
			offeredAimBoxTargets.clear();
			offeredAimBoxFrame = clientFrame;
		}
		return !offeredAimBoxTargets.add(targetKey);
	}

	private boolean aimBoxEntryAlreadyPresent(MenuEntry source)
	{
		String sourceKey = aimBoxTargetKey(source);
		if (sourceKey == null)
		{
			return false;
		}

		for (MenuEntry entry : client.getMenuEntries())
		{
			if (AIM_BOX_OPTION.equals(entry.getOption())
				&& entry.getType() == MenuAction.RUNELITE
				&& sourceKey.equals(aimBoxTargetKey(entry)))
			{
				return true;
			}
		}
		return false;
	}

	private String aimBoxTargetKey(MenuEntry entry)
	{
		if (entry == null)
		{
			return null;
		}

		Actor actor = actorForEntry(entry);
		if (actor != null)
		{
			return "actor:" + System.identityHashCode(actor);
		}

		if (isInventoryEntry(entry))
		{
			return "inventory:" + entry.getParam1() + ':' + entry.getParam0() + ':' + entry.getItemId();
		}

		if (isSpellEntry(entry))
		{
			return "spell:" + widgetIdForEntry(entry);
		}
		return null;
	}

	private boolean isInventoryEntry(MenuEntry entry)
	{
		return entry != null
			&& (isInventoryWidgetId(entry.getParam1()) || isInventoryWidget(entry.getWidget()));
	}

	private boolean isSpellEntry(MenuEntry entry)
	{
		return entry != null
			&& (isSpellbookWidgetId(entry.getParam1()) || isSpellbookWidget(entry.getWidget()));
	}

	private boolean isActorEntry(MenuEntry entry)
	{
		return actorForEntry(entry) != null;
	}

	private Actor actorForEntry(MenuEntry entry)
	{
		if (entry == null)
		{
			return null;
		}

		Actor actor = entry.getActor();
		if (actor != null)
		{
			return actor;
		}
		if (entry.getPlayer() != null)
		{
			return entry.getPlayer();
		}
		return entry.getNpc();
	}

	private int widgetIdForEntry(MenuEntry entry)
	{
		if (entry == null)
		{
			return -1;
		}

		Widget widget = entry.getWidget();
		return widget != null ? widget.getId() : entry.getParam1();
	}

	private boolean isInventoryAction(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		if (action == MenuAction.RUNELITE || action == MenuAction.RUNELITE_OVERLAY || action == MenuAction.CANCEL)
		{
			return false;
		}

		boolean inventoryAction = isInventoryWidgetId(event.getParam1()) || isInventoryWidget(event.getWidget());
		return inventoryAction && shouldBlockDirectInventoryClick();
	}

	private boolean isInventoryFallbackAction(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		return action != MenuAction.RUNELITE
			&& action != MenuAction.RUNELITE_OVERLAY
			&& action != MenuAction.CANCEL
			&& directInventoryFallbackBlockUntilNanos >= 0
			&& System.nanoTime() <= directInventoryFallbackBlockUntilNanos;
	}

	private boolean isMarkedSpellAction(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		if (action == MenuAction.RUNELITE || action == MenuAction.RUNELITE_OVERLAY || action == MenuAction.CANCEL)
		{
			return false;
		}

		if (!shouldBlockDirectMarkedSpellClick())
		{
			return false;
		}

		int widgetId = event.getParam1();
		for (WidgetAimBox box : spellBoxes)
		{
			if (box.widgetId == widgetId)
			{
				return true;
			}
		}

		Widget widget = event.getWidget();
		if (widget == null)
		{
			return false;
		}

		for (WidgetAimBox box : spellBoxes)
		{
			if (widget.getId() == box.widgetId)
			{
				return true;
			}
		}
		return false;
	}

	private boolean isOverlayClickAction(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		return action != MenuAction.RUNELITE
			&& action != MenuAction.RUNELITE_OVERLAY
			&& action != MenuAction.CANCEL
			&& directOverlayBlockUntilNanos >= 0
			&& System.nanoTime() <= directOverlayBlockUntilNanos;
	}

	private boolean isGameWorldClickAction(MenuOptionClicked event)
	{
		if (directGameWorldBlockUntilNanos < 0 || System.nanoTime() > directGameWorldBlockUntilNanos)
		{
			return false;
		}

		switch (event.getMenuAction())
		{
			case WALK:
			case ITEM_USE_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case ITEM_USE_ON_NPC:
			case WIDGET_TARGET_ON_NPC:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case ITEM_USE_ON_PLAYER:
			case WIDGET_TARGET_ON_PLAYER:
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			case ITEM_USE_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	private void trackInventoryDirectPress(MouseEvent mouseEvent)
	{
		clearCurrentInventoryDirectClickState();
		expirePendingInventoryActionBlocks();
		if (!config.enabled() || isPianoModeActive() || !isLeftMouse(mouseEvent) || !isClientCanvasMouseEvent(mouseEvent) || isMenuInteraction(mouseEvent))
		{
			return;
		}

		InventorySlot slot = inventorySlotAtCached(mouseEvent.getX(), mouseEvent.getY());
		if (slot == null)
		{
			return;
		}

		inventoryDirectPressActive = true;
		inventoryDirectPressDragged = false;
		inventoryDirectPressX = mouseEvent.getX();
		inventoryDirectPressY = mouseEvent.getY();
	}

	private void trackInventoryDirectDrag(MouseEvent mouseEvent)
	{
		if (!inventoryDirectPressActive || !isClientCanvasMouseEvent(mouseEvent))
		{
			return;
		}

		if (markedInventoryAimPressActive)
		{
			mouseEvent.consume();
			return;
		}

		if (Math.abs(mouseEvent.getX() - inventoryDirectPressX) > INVENTORY_DRAG_THRESHOLD
			|| Math.abs(mouseEvent.getY() - inventoryDirectPressY) > INVENTORY_DRAG_THRESHOLD)
		{
			inventoryDirectPressDragged = true;
			consumeTrainingRelease = false;
			consumeTrainingClick = false;
		}
	}

	private void trackInventoryDirectRelease(MouseEvent mouseEvent)
	{
		if (inventoryDirectPressActive && isLeftMouse(mouseEvent))
		{
			if (!inventoryDirectPressDragged)
			{
				queueInventoryActionBlock();
				directInventoryFallbackBlockUntilNanos = actionBlockUntilNanos();
			}
			clearCurrentInventoryDirectClickState();
		}
	}

	private boolean shouldBlockDirectInventoryClick()
	{
		expirePendingInventoryActionBlocks();
		if (pendingInventoryActionBlocks > 0)
		{
			pendingInventoryActionBlocks--;
			if (pendingInventoryActionBlocks == 0)
			{
				pendingInventoryActionBlockUntilNanos = -1L;
			}
			return true;
		}

		boolean shouldBlock = inventoryDirectPressActive && !inventoryDirectPressDragged;
		if (shouldBlock)
		{
			clearCurrentInventoryDirectClickState();
		}
		return shouldBlock;
	}

	private boolean shouldBlockDirectMarkedSpellClick()
	{
		return directMarkedSpellBlockUntilNanos >= 0 && System.nanoTime() <= directMarkedSpellBlockUntilNanos;
	}

	private void clearStaleDirectClickState()
	{
		long now = System.nanoTime();
		if (pendingInventoryActionBlockUntilNanos >= 0 && now > pendingInventoryActionBlockUntilNanos)
		{
			clearPendingInventoryActionBlocks();
		}

		if (directInventoryFallbackBlockUntilNanos >= 0 && now > directInventoryFallbackBlockUntilNanos)
		{
			directInventoryFallbackBlockUntilNanos = -1L;
		}

		if (directMarkedSpellBlockUntilNanos >= 0 && now > directMarkedSpellBlockUntilNanos)
		{
			directMarkedSpellBlockUntilNanos = -1L;
		}

		if (directOverlayBlockUntilNanos >= 0 && now > directOverlayBlockUntilNanos)
		{
			directOverlayBlockUntilNanos = -1L;
		}

		if (directGameWorldBlockUntilNanos >= 0 && now > directGameWorldBlockUntilNanos)
		{
			directGameWorldBlockUntilNanos = -1L;
		}
	}

	private void queueInventoryActionBlock()
	{
		pendingInventoryActionBlocks = Math.min(8, pendingInventoryActionBlocks + 1);
		pendingInventoryActionBlockUntilNanos = actionBlockUntilNanos();
	}

	private void expirePendingInventoryActionBlocks()
	{
		if (pendingInventoryActionBlockUntilNanos >= 0 && System.nanoTime() > pendingInventoryActionBlockUntilNanos)
		{
			clearPendingInventoryActionBlocks();
		}
	}

	private void clearInventoryDirectClickState()
	{
		clearCurrentInventoryDirectClickState();
		clearPendingInventoryActionBlocks();
		directInventoryFallbackBlockUntilNanos = -1L;
	}

	private void clearCurrentInventoryDirectClickState()
	{
		inventoryDirectPressActive = false;
		inventoryDirectPressDragged = false;
		markedInventoryAimPressActive = false;
		inventoryDirectPressX = -1;
		inventoryDirectPressY = -1;
	}

	private void clearPendingInventoryActionBlocks()
	{
		pendingInventoryActionBlocks = 0;
		pendingInventoryActionBlockUntilNanos = -1L;
	}

	private void clearDirectActionBlocksForMenuInteraction()
	{
		clearTransientInputState();
	}

	private void clearTransientInputState()
	{
		clearInventoryDirectClickState();
		consumeTrainingRelease = false;
		consumeTrainingClick = false;
		markedInventoryAimPressActive = false;
		directMarkedSpellBlockUntilNanos = -1L;
		directOverlayBlockUntilNanos = -1L;
		directGameWorldBlockUntilNanos = -1L;
	}

	private Rectangle resolveInventoryBounds(InventoryAimBox box)
	{
		Rectangle bounds = resolveCurrentInventoryAimBounds(box);
		box.activeBounds = bounds;
		return bounds != null ? bounds : box.lastBounds;
	}

	private Rectangle resolveCurrentInventoryAimBounds(InventoryAimBox box)
	{
		Widget slot = inventoryWidgetForSlot(box.slotIndex);
		if (slot != null && slot.getItemId() == box.itemId)
		{
			Rectangle bounds = inventorySlotBounds(slot);
			if (bounds != null)
			{
				box.lastBounds = bounds;
				return bounds;
			}
		}

		Widget movedSlot = nearestInventorySlotWithItem(box.itemId, box.lastBounds);
		if (movedSlot != null)
		{
			Rectangle bounds = inventorySlotBounds(movedSlot);
			if (bounds != null)
			{
				box.slotIndex = movedSlot.getIndex();
				box.lastBounds = bounds;
				return bounds;
			}
		}

		return null;
	}

	private Rectangle resolveWidgetBounds(WidgetAimBox box)
	{
		Rectangle bounds = resolveCurrentWidgetAimBounds(box);
		box.activeBounds = bounds;
		return bounds != null ? bounds : box.lastBounds;
	}

	private Rectangle resolveCurrentWidgetAimBounds(WidgetAimBox box)
	{
		Widget widget = client.getWidget(box.widgetId);
		Rectangle bounds = validBounds(widget);
		if (bounds != null)
		{
			box.lastBounds = bounds;
			return bounds;
		}

		return null;
	}

	private Widget inventoryWidgetForSlot(int slotIndex)
	{
		if (slotIndex < 0 || slotIndex >= 28)
		{
			return null;
		}

		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory == null || inventory.isHidden())
		{
			return null;
		}

		Widget slot = inventory.getChild(slotIndex);
		return slot == null || slot.isHidden() ? null : slot;
	}

	private void refreshInventorySlotSnapshot()
	{
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory == null || inventory.isHidden())
		{
			inventorySlotSnapshot = new InventorySlot[0];
			return;
		}

		InventorySlot[] slots = new InventorySlot[28];
		int count = 0;
		for (int slotIndex = 0; slotIndex < 28; slotIndex++)
		{
			Widget item = inventory.getChild(slotIndex);
			if (item == null || item.isHidden() || item.getItemId() <= 0)
			{
				continue;
			}

			Rectangle bounds = inventorySlotBounds(item);
			if (bounds != null)
			{
				slots[count++] = new InventorySlot(slotIndex, item.getItemId(), bounds);
			}
		}

		if (count == slots.length)
		{
			inventorySlotSnapshot = slots;
			return;
		}

		InventorySlot[] compact = new InventorySlot[count];
		System.arraycopy(slots, 0, compact, 0, count);
		inventorySlotSnapshot = compact;
	}

	private void refreshAimBoxBounds()
	{
		for (InventoryAimBox box : inventoryBoxes)
		{
			resolveInventoryBounds(box);
		}

		for (WidgetAimBox box : spellBoxes)
		{
			resolveWidgetBounds(box);
		}

		refreshActorAimBoxBounds();
	}

	private void refreshClientThreadState()
	{
		refreshInventorySlotSnapshot();
		refreshAimBoxBounds();
		if (config.enabled())
		{
			ensureGameTargets();
		}
		repaintClientCanvas();
	}

	private Widget nearestInventorySlotWithItem(int itemId, Rectangle previousBounds)
	{
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (inventory == null || inventory.isHidden())
		{
			return null;
		}

		List<Widget> candidates = new ArrayList<>();
		for (int slot = 0; slot < 28; slot++)
		{
			Widget item = inventory.getChild(slot);
			if (item != null && !item.isHidden() && item.getItemId() == itemId && inventorySlotBounds(item) != null)
			{
				candidates.add(item);
			}
		}

		if (candidates.isEmpty())
		{
			return null;
		}

		if (previousBounds == null)
		{
			return candidates.get(0);
		}

		final int centerX = previousBounds.x + previousBounds.width / 2;
		final int centerY = previousBounds.y + previousBounds.height / 2;
		return candidates.stream()
			.min(Comparator.comparingInt(item ->
			{
				Rectangle bounds = inventorySlotBounds(item);
				int dx = bounds.x + bounds.width / 2 - centerX;
				int dy = bounds.y + bounds.height / 2 - centerY;
				return dx * dx + dy * dy;
			}))
			.orElse(candidates.get(0));
	}

	private InventorySlot inventorySlotAtCached(int x, int y)
	{
		for (InventorySlot slot : inventorySlotSnapshot)
		{
			if (contains(slot.bounds, x, y))
			{
				return slot;
			}
		}
		return null;
	}

	private Rectangle validBounds(Widget widget)
	{
		if (widget == null || widget.isHidden())
		{
			return null;
		}

		Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return null;
		}

		return new Rectangle(bounds);
	}

	private Shape validActorShape(Actor actor)
	{
		if (actor == null)
		{
			return null;
		}

		Shape shape = actor.getConvexHull();
		if (shape == null)
		{
			return null;
		}

		Rectangle bounds = shape.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return null;
		}
		return new Area(shape);
	}

	private void refreshActorAimBoxBounds()
	{
		for (ActorAimBox box : actorBoxes)
		{
			Shape shape = validActorShape(box.actor);
			box.activeShape = shape;
			if (shape != null)
			{
				box.lastShape = shape;
			}
		}
	}

	private Rectangle inventorySlotBounds(Widget widget)
	{
		Rectangle bounds = validBounds(widget);
		if (bounds == null)
		{
			return null;
		}

		int widgetWidth = widget.getWidth();
		int widgetHeight = widget.getHeight();
		if (widgetWidth > bounds.width && widgetWidth <= MAX_INVENTORY_SLOT_DIMENSION)
		{
			bounds.width = widgetWidth;
		}
		if (widgetHeight > bounds.height && widgetHeight <= MAX_INVENTORY_SLOT_DIMENSION)
		{
			bounds.height = widgetHeight;
		}
		return bounds;
	}

	private boolean isInventoryWidget(Widget widget)
	{
		return widget != null && isInventoryWidgetId(widget.getId());
	}

	private boolean isSpellbookWidget(Widget widget)
	{
		return widget != null && isSpellbookWidgetId(widget.getId());
	}

	private boolean isInventoryWidgetId(int widgetId)
	{
		return widgetId > 0 && (widgetId >>> 16) == INVENTORY_GROUP;
	}

	private boolean isSpellbookWidgetId(int widgetId)
	{
		return widgetId > 0 && (widgetId >>> 16) == SPELLBOOK_GROUP;
	}

	private boolean contains(Rectangle bounds, int x, int y)
	{
		return bounds != null && x >= bounds.x && x < bounds.x + bounds.width && y >= bounds.y && y < bounds.y + bounds.height;
	}

	private boolean contains(Shape shape, int x, int y)
	{
		return shape != null && shape.contains(x, y);
	}

	private boolean containsGameBox(GameAimBox box, int x, int y)
	{
		if (box == null || box.bounds == null)
		{
			return false;
		}

		if (gameTargetShape() == NhAimTrainerConfig.GameTargetShape.CIRCLES)
		{
			return new Ellipse2D.Double(box.bounds.x, box.bounds.y, box.bounds.width, box.bounds.height).contains(x, y);
		}
		return contains(box.bounds, x, y);
	}

	private boolean isDrawable(long hiddenUntilNanos)
	{
		return hiddenUntilNanos < 0 || System.nanoTime() >= hiddenUntilNanos;
	}

	private long feedbackHiddenUntilNanos()
	{
		return System.nanoTime() + OSRS_GAME_TICK_NANOS;
	}

	private long actionBlockUntilNanos()
	{
		return System.nanoTime() + OSRS_GAME_TICK_NANOS;
	}

	private boolean isClientCanvasMouseEvent(MouseEvent mouseEvent)
	{
		return mouseEvent != null && client != null && mouseEvent.getSource() == client.getCanvas();
	}

	private boolean isLeftMouse(MouseEvent mouseEvent)
	{
		return mouseEvent != null && (SwingUtilities.isLeftMouseButton(mouseEvent) || mouseEvent.getButton() == MouseEvent.BUTTON1);
	}

	private void recordOpenMenuBounds()
	{
		if (!client.isMenuOpen())
		{
			menuOpen = false;
			return;
		}

		menuOpen = true;
		lastMenuOpenFrame = clientFrame;
		lastMenuX = client.getMenuX();
		lastMenuY = client.getMenuY();
		lastMenuWidth = client.getMenuWidth();
		lastMenuHeight = client.getMenuHeight();
	}

	private void clearMenuBounds()
	{
		menuOpen = false;
		lastMenuOpenFrame = -1;
		lastMenuX = 0;
		lastMenuY = 0;
		lastMenuWidth = 0;
		lastMenuHeight = 0;
	}

	private boolean isMenuInteraction(MouseEvent mouseEvent)
	{
		return menuOpen
			|| lastMenuOpenFrame >= 0
			&& clientFrame - lastMenuOpenFrame <= 2
			&& lastMenuWidth > 0
			&& lastMenuHeight > 0
			&& contains(new Rectangle(lastMenuX, lastMenuY, lastMenuWidth, lastMenuHeight), mouseEvent.getX(), mouseEvent.getY());
	}

	private boolean isGameViewportClick(int x, int y)
	{
		int viewportX = client.getViewportXOffset();
		int viewportY = client.getViewportYOffset();
		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		return viewportWidth > 0
			&& viewportHeight > 0
			&& x >= viewportX
			&& x < viewportX + viewportWidth
			&& y >= viewportY
			&& y < viewportY + viewportHeight;
	}

	private void ensureGameTargets()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			gameBoxes.clear();
			return;
		}

		while (gameBoxes.size() < GAME_TARGET_COUNT)
		{
			Rectangle bounds = randomViewportTargetBounds();
			if (bounds == null)
			{
				return;
			}
			gameBoxes.add(new GameAimBox(bounds, System.nanoTime()));
		}
	}

	private void respawnGameTarget(GameAimBox box)
	{
		Rectangle bounds = randomViewportTargetBounds();
		if (bounds == null)
		{
			box.hiddenUntilNanos = feedbackHiddenUntilNanos();
			return;
		}

		box.bounds = bounds;
		box.hiddenUntilNanos = feedbackHiddenUntilNanos();
		box.spawnedAtNanos = box.hiddenUntilNanos;
	}

	private Rectangle randomViewportTargetBounds()
	{
		int viewportX = client.getViewportXOffset();
		int viewportY = client.getViewportYOffset();
		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		if (viewportWidth <= 80 || viewportHeight <= 80)
		{
			return null;
		}

		int targetWidth = randomGameTargetSize(viewportWidth, viewportHeight);
		int targetHeight = targetWidth;

		int marginX = Math.min(70, viewportWidth / 7);
		int marginY = Math.min(60, viewportHeight / 7);
		int minX = viewportX + marginX;
		int minY = viewportY + marginY;
		int maxX = viewportX + viewportWidth - marginX - targetWidth;
		int maxY = viewportY + viewportHeight - marginY - targetHeight;
		if (maxX <= minX || maxY <= minY)
		{
			minX = viewportX;
			minY = viewportY;
			maxX = viewportX + viewportWidth - targetWidth;
			maxY = viewportY + viewportHeight - targetHeight;
		}

		int x = minX + random.nextInt(Math.max(1, maxX - minX + 1));
		int y = minY + random.nextInt(Math.max(1, maxY - minY + 1));
		return new Rectangle(x, y, targetWidth, targetHeight);
	}

	private int randomGameTargetSize(int viewportWidth, int viewportHeight)
	{
		int average = clampGameTargetAverageSize(config == null ? 36 : config.gameTargetAverageSize());
		int variance = Math.max(3, Math.round(average * 0.18f));
		int size = average + random.nextInt(variance * 2 + 1) - variance;
		int maxSize = Math.max(GAME_TARGET_MIN_SIZE, Math.min(GAME_TARGET_MAX_SIZE, Math.min(viewportWidth, viewportHeight) - 8));
		return Math.max(GAME_TARGET_MIN_SIZE, Math.min(maxSize, size));
	}

	private int clampGameTargetAverageSize(int size)
	{
		return Math.max(GAME_TARGET_MIN_SIZE, Math.min(GAME_TARGET_MAX_SIZE, size));
	}

	private NhAimTrainerConfig.GameTargetShape gameTargetShape()
	{
		NhAimTrainerConfig.GameTargetShape shape = config == null ? null : config.gameTargetShape();
		return shape == null ? NhAimTrainerConfig.GameTargetShape.SQUARES : shape;
	}

	private void repaintClientCanvas()
	{
		if (client != null && client.getCanvas() != null)
		{
			client.getCanvas().repaint();
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
			graphics.drawString("A", 5, 11);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private final class AimTrainerOverlay extends Overlay
	{
		private AimTrainerOverlay()
		{
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ALWAYS_ON_TOP);
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			if (!config.enabled())
			{
				return null;
			}

			int alpha = Math.round(255.0f * Math.max(40, Math.min(95, config.boxOpacity())) / 100.0f);
			if (isPianoModeActive())
			{
				drawPianoTiles(graphics, alpha);
				return null;
			}

			refreshActorAimBoxBounds();

			for (InventoryAimBox box : inventoryBoxes)
			{
				if (isDrawable(box.hiddenUntilNanos))
				{
					drawAimBox(graphics, box.lastBounds, alpha);
				}
			}

			for (WidgetAimBox box : spellBoxes)
			{
				if (isDrawable(box.hiddenUntilNanos))
				{
					drawAimBox(graphics, box.lastBounds, alpha);
				}
			}

			for (ActorAimBox box : actorBoxes)
			{
				if (isDrawable(box.hiddenUntilNanos))
				{
					drawAimShape(graphics, box.activeShape, alpha);
				}
			}

			long now = System.nanoTime();
			for (GameAimBox box : gameBoxes)
			{
				if (box.bounds == null)
				{
					gameBoxes.remove(box);
					continue;
				}

				if (!isDrawable(box.hiddenUntilNanos))
				{
					continue;
				}

				float fade = Math.min(1.0f, Math.max(0.0f, (now - box.spawnedAtNanos) / (float) GAME_TARGET_FADE_NANOS));
				drawGameAimBox(graphics, box, Math.round(alpha * fade));
			}

			return null;
		}

		private void drawPianoTiles(Graphics2D graphics, int alpha)
		{
			Rectangle viewport = viewportBounds();
			if (viewport == null)
			{
				return;
			}

			for (PianoTile tile : pianoTiles)
			{
				drawAimBox(graphics, pianoTileBounds(tile), alpha);
			}

			drawPianoStatus(graphics, viewport);
		}

		private void drawPianoStatus(Graphics2D graphics, Rectangle viewport)
		{
			String status = pianoRunning ? "Score: " + pianoScore : pianoStatusText();
			graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 18.0f));
			int x = viewport.x + 10;
			int y = viewport.y + 26;
			int width = Math.max(160, graphics.getFontMetrics().stringWidth(status) + 18);
			graphics.setColor(new Color(0, 0, 0, 150));
			graphics.fillRoundRect(x - 6, y - 20, width, 28, 8, 8);
			graphics.setColor(Color.WHITE);
			graphics.drawString(status, x, y);
		}

		private void drawAimBox(Graphics2D graphics, Rectangle bounds, int alpha)
		{
			if (bounds == null || alpha <= 0)
			{
				return;
			}

			graphics.setColor(new Color(0, 0, 0, Math.max(0, Math.min(255, alpha))));
			graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
			graphics.setColor(Color.WHITE);
			graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
		}

		private void drawGameAimBox(Graphics2D graphics, GameAimBox box, int alpha)
		{
			if (box == null || box.bounds == null)
			{
				return;
			}

			if (gameTargetShape() == NhAimTrainerConfig.GameTargetShape.CIRCLES)
			{
				drawAimShape(graphics, new Ellipse2D.Double(box.bounds.x, box.bounds.y, box.bounds.width, box.bounds.height), alpha);
				return;
			}
			drawAimBox(graphics, box.bounds, alpha);
		}

		private void drawAimShape(Graphics2D graphics, Shape shape, int alpha)
		{
			if (shape == null || alpha <= 0)
			{
				return;
			}

			graphics.setColor(new Color(0, 0, 0, Math.max(0, Math.min(255, alpha))));
			graphics.fill(shape);
			graphics.setColor(Color.WHITE);
			graphics.draw(shape);
		}
	}

	private static final class InventoryAimBox
	{
		private int slotIndex;
		private final int itemId;
		private Rectangle lastBounds;
		private Rectangle activeBounds;
		private long hiddenUntilNanos = -1L;

		private InventoryAimBox(int slotIndex, int itemId, Rectangle lastBounds)
		{
			this.slotIndex = slotIndex;
			this.itemId = itemId;
			this.lastBounds = lastBounds;
			this.activeBounds = lastBounds;
		}
	}

	private static final class InventorySlot
	{
		private final int slotIndex;
		private final int itemId;
		private final Rectangle bounds;

		private InventorySlot(int slotIndex, int itemId, Rectangle bounds)
		{
			this.slotIndex = slotIndex;
			this.itemId = itemId;
			this.bounds = bounds;
		}
	}

	private static final class WidgetAimBox
	{
		private final int widgetId;
		private Rectangle lastBounds;
		private Rectangle activeBounds;
		private long hiddenUntilNanos = -1L;

		private WidgetAimBox(int widgetId, Rectangle lastBounds)
		{
			this.widgetId = widgetId;
			this.lastBounds = lastBounds;
			this.activeBounds = lastBounds;
		}
	}

	private static final class GameAimBox
	{
		private Rectangle bounds;
		private long spawnedAtNanos;
		private long hiddenUntilNanos = -1L;

		private GameAimBox(Rectangle bounds, long spawnedAtNanos)
		{
			this.bounds = bounds;
			this.spawnedAtNanos = spawnedAtNanos;
		}
	}

	private static final class ActorAimBox
	{
		private final Actor actor;
		private Shape lastShape;
		private Shape activeShape;
		private long hiddenUntilNanos = -1L;

		private ActorAimBox(Actor actor, Shape lastShape)
		{
			this.actor = actor;
			this.lastShape = lastShape;
			this.activeShape = lastShape;
		}
	}

	private static final class PianoTile
	{
		private final int lane;
		private double y;

		private PianoTile(int lane, double y)
		{
			this.lane = lane;
			this.y = y;
		}
	}

	private static final class PianoScore
	{
		private final int score;
		private final long timestampMillis;

		private PianoScore(int score, long timestampMillis)
		{
			this.score = score;
			this.timestampMillis = timestampMillis;
		}
	}

	private static final class AimHit
	{
		private final AimHitType type;
		private final InventoryAimBox inventoryBox;
		private final WidgetAimBox widgetBox;
		private final GameAimBox gameBox;
		private final ActorAimBox actorBox;

		private AimHit(AimHitType type, InventoryAimBox inventoryBox, WidgetAimBox widgetBox, GameAimBox gameBox, ActorAimBox actorBox)
		{
			this.type = type;
			this.inventoryBox = inventoryBox;
			this.widgetBox = widgetBox;
			this.gameBox = gameBox;
			this.actorBox = actorBox;
		}

		private static AimHit inventory(InventoryAimBox box)
		{
			return new AimHit(AimHitType.INVENTORY, box, null, null, null);
		}

		private static AimHit spell(WidgetAimBox box)
		{
			return new AimHit(AimHitType.SPELL, null, box, null, null);
		}

		private static AimHit game(GameAimBox box)
		{
			return new AimHit(AimHitType.GAME, null, null, box, null);
		}

		private static AimHit actor(ActorAimBox box)
		{
			return new AimHit(AimHitType.ACTOR, null, null, null, box);
		}
	}

	private enum AimHitType
	{
		INVENTORY,
		SPELL,
		GAME,
		ACTOR
	}
}
