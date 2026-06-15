package com.nhtrainer.inputtweaks;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("nhaimtrainer")
public interface NhAimTrainerConfig extends Config
{
	String GROUP = "nhaimtrainer";
	String ENABLED = "enabled";
	String BOX_OPACITY = "boxOpacity";
	String PIANO_TILES_ENABLED = "pianoTilesEnabled";
	String PIANO_TILE_AUDIO_ENABLED = "pianoTileAudioEnabled";
	String PIANO_TILE_AUDIO_VOLUME = "pianoTileAudioVolume";
	String PIANO_TILE_SCORES = "pianoTileScores";
	String GAME_TARGET_SHAPE = "gameTargetShape";
	String GAME_TARGET_AVERAGE_SIZE = "gameTargetAverageSize";

	enum GameTargetShape
	{
		SQUARES,
		CIRCLES
	}

	@ConfigItem(
		keyName = ENABLED,
		name = "Enable aim trainer",
		description = "Shows aim boxes and blocks trained left-click actions while enabled.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}

	@Range(
		min = 40,
		max = 95
	)
	@ConfigItem(
		keyName = BOX_OPACITY,
		name = "Box opacity",
		description = "Opacity of the black aim boxes.",
		position = 2
	)
	default int boxOpacity()
	{
		return 86;
	}

	@ConfigItem(
		keyName = GAME_TARGET_SHAPE,
		name = "Game target shape",
		description = "Shape used by random game-view aim targets.",
		position = 3
	)
	default GameTargetShape gameTargetShape()
	{
		return GameTargetShape.SQUARES;
	}

	@Range(
		min = 14,
		max = 96
	)
	@ConfigItem(
		keyName = GAME_TARGET_AVERAGE_SIZE,
		name = "Game target size",
		description = "Average size of random game-view aim targets.",
		position = 4
	)
	default int gameTargetAverageSize()
	{
		return 36;
	}

	@ConfigItem(
		keyName = PIANO_TILES_ENABLED,
		name = "Piano tiles mode",
		description = "Replaces aim boxes with falling piano tiles and catches left-clicks on the client.",
		position = 5
	)
	default boolean pianoTilesEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = PIANO_TILE_AUDIO_ENABLED,
		name = "Piano sounds",
		description = "Plays a short rising tone when a piano tile is clicked.",
		position = 6
	)
	default boolean pianoTileAudioEnabled()
	{
		return true;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = PIANO_TILE_AUDIO_VOLUME,
		name = "Piano volume",
		description = "Volume for piano tile click sounds. Zero is silent.",
		position = 7
	)
	default int pianoTileAudioVolume()
	{
		return 35;
	}

	@ConfigItem(
		keyName = PIANO_TILE_SCORES,
		name = "Local piano scores",
		description = "Local piano tiles score history.",
		position = 8,
		hidden = true
	)
	default String pianoTileScores()
	{
		return "";
	}
}
