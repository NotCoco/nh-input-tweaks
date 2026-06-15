package com.nhtrainer.inputtweaks;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

class NhAimTrainerPanel extends PluginPanel
{
	private final ConfigManager configManager;
	private final NhAimTrainerConfig config;
	private final Runnable clearAction;
	private final Runnable resetPianoAction;
	private final Runnable savePianoScoreAction;
	private final Supplier<String> pianoStatusSupplier;
	private final Supplier<String> pianoLeaderboardSupplier;
	private final JCheckBox enabledCheckBox = new JCheckBox("Enable aim trainer");
	private final JCheckBox pianoTilesCheckBox = new JCheckBox("Piano tiles mode");
	private final JCheckBox pianoAudioCheckBox = new JCheckBox("Piano sounds");
	private final JComboBox<NhAimTrainerConfig.GameTargetShape> targetShapeComboBox = new JComboBox<>(NhAimTrainerConfig.GameTargetShape.values());
	private final JSlider opacitySlider = new JSlider(40, 95);
	private final JSpinner opacitySpinner = new JSpinner(new SpinnerNumberModel(86, 40, 95, 1));
	private final JSlider targetSizeSlider = new JSlider(14, 96);
	private final JSpinner targetSizeSpinner = new JSpinner(new SpinnerNumberModel(36, 14, 96, 1));
	private final JSlider pianoVolumeSlider = new JSlider(0, 100);
	private final JSpinner pianoVolumeSpinner = new JSpinner(new SpinnerNumberModel(35, 0, 100, 1));
	private final JLabel opacityValue = new JLabel();
	private final JLabel targetSizeValue = new JLabel();
	private final JLabel pianoVolumeValue = new JLabel();
	private final JLabel pianoStatus = new JLabel();
	private boolean updating;

	NhAimTrainerPanel(
		ConfigManager configManager,
		NhAimTrainerConfig config,
		Runnable clearAction,
		Runnable resetPianoAction,
		Runnable savePianoScoreAction,
		Supplier<String> pianoStatusSupplier,
		Supplier<String> pianoLeaderboardSupplier)
	{
		this.configManager = configManager;
		this.config = config;
		this.clearAction = clearAction;
		this.resetPianoAction = resetPianoAction;
		this.savePianoScoreAction = savePianoScoreAction;
		this.pianoStatusSupplier = pianoStatusSupplier;
		this.pianoLeaderboardSupplier = pianoLeaderboardSupplier;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(12, 10, 10, 10));
		add(content, BorderLayout.NORTH);

		JLabel title = new JLabel("Aim Trainer", SwingConstants.LEFT);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.setForeground(ColorScheme.TEXT_COLOR);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16.0f));
		content.add(title);
		content.add(Box.createVerticalStrut(12));

		content.add(settingPanel("Training", trainingControlPanel()));
		content.add(Box.createVerticalStrut(10));
		content.add(settingPanel("Piano tiles", pianoTilesControlPanel()));
		content.add(Box.createVerticalStrut(10));
		content.add(settingPanel("Aim boxes", aimBoxControlPanel()));

		enabledCheckBox.setOpaque(false);
		enabledCheckBox.setForeground(ColorScheme.TEXT_COLOR);
		enabledCheckBox.addActionListener(event ->
		{
			if (!updating)
			{
				configManager.setConfiguration(
					NhAimTrainerConfig.GROUP,
					NhAimTrainerConfig.ENABLED,
					enabledCheckBox.isSelected());
			}
		});

		pianoTilesCheckBox.setOpaque(false);
		pianoTilesCheckBox.setForeground(ColorScheme.TEXT_COLOR);
		pianoTilesCheckBox.addActionListener(event ->
		{
			if (!updating)
			{
				configManager.setConfiguration(
					NhAimTrainerConfig.GROUP,
					NhAimTrainerConfig.PIANO_TILES_ENABLED,
					pianoTilesCheckBox.isSelected());
			}
		});

		pianoAudioCheckBox.setOpaque(false);
		pianoAudioCheckBox.setForeground(ColorScheme.TEXT_COLOR);
		pianoAudioCheckBox.addActionListener(event ->
		{
			if (!updating)
			{
				configManager.setConfiguration(
					NhAimTrainerConfig.GROUP,
					NhAimTrainerConfig.PIANO_TILE_AUDIO_ENABLED,
					pianoAudioCheckBox.isSelected());
			}
		});

		targetShapeComboBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		targetShapeComboBox.addActionListener(event ->
		{
			if (!updating)
			{
				configManager.setConfiguration(
					NhAimTrainerConfig.GROUP,
					NhAimTrainerConfig.GAME_TARGET_SHAPE,
					targetShapeComboBox.getSelectedItem());
			}
		});

		opacitySlider.setOpaque(false);
		opacitySlider.setMajorTickSpacing(15);
		opacitySlider.setPaintTicks(true);
		opacitySlider.addChangeListener(event ->
		{
			if (!updating)
			{
				setOpacity(opacitySlider.getValue());
			}
		});

		opacitySpinner.addChangeListener(event ->
		{
			if (!updating)
			{
				setOpacity((Integer) opacitySpinner.getValue());
			}
		});

		targetSizeSlider.setOpaque(false);
		targetSizeSlider.setMajorTickSpacing(20);
		targetSizeSlider.setPaintTicks(true);
		targetSizeSlider.addChangeListener(event ->
		{
			if (!updating)
			{
				setTargetSize(targetSizeSlider.getValue());
			}
		});

		targetSizeSpinner.addChangeListener(event ->
		{
			if (!updating)
			{
				setTargetSize((Integer) targetSizeSpinner.getValue());
			}
		});

		pianoVolumeSlider.setOpaque(false);
		pianoVolumeSlider.setMajorTickSpacing(25);
		pianoVolumeSlider.setPaintTicks(true);
		pianoVolumeSlider.addChangeListener(event ->
		{
			if (!updating)
			{
				setPianoVolume(pianoVolumeSlider.getValue());
			}
		});

		pianoVolumeSpinner.addChangeListener(event ->
		{
			if (!updating)
			{
				setPianoVolume((Integer) pianoVolumeSpinner.getValue());
			}
		});

		refresh();
	}

	void refresh()
	{
		updating = true;
		try
		{
			enabledCheckBox.setSelected(config.enabled());
			pianoTilesCheckBox.setSelected(config.pianoTilesEnabled());
			pianoAudioCheckBox.setSelected(config.pianoTileAudioEnabled());
			targetShapeComboBox.setSelectedItem(config.gameTargetShape());
			int opacity = clampOpacity(config.boxOpacity());
			int targetSize = clampTargetSize(config.gameTargetAverageSize());
			int pianoVolume = clampPianoVolume(config.pianoTileAudioVolume());
			opacitySlider.setValue(opacity);
			opacitySpinner.setValue(opacity);
			targetSizeSlider.setValue(targetSize);
			targetSizeSpinner.setValue(targetSize);
			pianoVolumeSlider.setValue(pianoVolume);
			pianoVolumeSpinner.setValue(pianoVolume);
			updateOpacityValue(opacity);
			updateTargetSizeValue(targetSize);
			updatePianoVolumeValue(pianoVolume);
			pianoStatus.setText(pianoStatusSupplier.get());
		}
		finally
		{
			updating = false;
		}
	}

	private JPanel settingPanel(String title, Component control)
	{
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel label = new JLabel(title);
		label.setForeground(ColorScheme.TEXT_COLOR);
		label.setFont(label.getFont().deriveFont(Font.BOLD));
		panel.add(label, BorderLayout.NORTH);
		panel.add(control, BorderLayout.CENTER);
		return panel;
	}

	private JPanel aimBoxControlPanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);

		JLabel shapeLabel = new JLabel("Game target shape");
		shapeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		shapeLabel.setForeground(ColorScheme.TEXT_COLOR);
		targetSizeValue.setAlignmentX(Component.LEFT_ALIGNMENT);
		targetSizeValue.setForeground(ColorScheme.TEXT_COLOR);

		panel.add(opacityControlPanel());
		panel.add(Box.createVerticalStrut(10));
		panel.add(shapeLabel);
		panel.add(Box.createVerticalStrut(4));
		panel.add(targetShapeComboBox);
		panel.add(Box.createVerticalStrut(10));
		panel.add(targetSizeControlPanel());
		return panel;
	}

	private JPanel trainingControlPanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);

		JButton clearButton = new JButton("Reset aim boxes");
		clearButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		clearButton.addActionListener(event -> clearAction.run());

		enabledCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(enabledCheckBox);
		panel.add(Box.createVerticalStrut(8));
		panel.add(clearButton);
		return panel;
	}

	private JPanel pianoTilesControlPanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);

		JButton restartButton = new JButton("Restart piano tiles");
		restartButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		restartButton.addActionListener(event -> resetPianoAction.run());

		JButton saveScoreButton = new JButton("Save local score");
		saveScoreButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		saveScoreButton.addActionListener(event ->
		{
			savePianoScoreAction.run();
			refresh();
		});

		JButton leaderboardButton = new JButton("Leaderboard");
		leaderboardButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		leaderboardButton.addActionListener(event -> showLeaderboard());

		pianoTilesCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		pianoAudioCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		pianoStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		pianoStatus.setForeground(ColorScheme.TEXT_COLOR);

		panel.add(pianoTilesCheckBox);
		panel.add(Box.createVerticalStrut(8));
		panel.add(pianoAudioCheckBox);
		panel.add(Box.createVerticalStrut(6));
		panel.add(pianoVolumeControlPanel());
		panel.add(Box.createVerticalStrut(8));
		panel.add(pianoStatus);
		panel.add(Box.createVerticalStrut(8));
		panel.add(restartButton);
		panel.add(Box.createVerticalStrut(6));
		panel.add(saveScoreButton);
		panel.add(Box.createVerticalStrut(6));
		panel.add(leaderboardButton);
		return panel;
	}

	private JPanel pianoVolumeControlPanel()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);

		pianoVolumeValue.setForeground(ColorScheme.TEXT_COLOR);

		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = 0;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets(0, 0, 6, 8);
		panel.add(pianoVolumeSlider, constraints);

		constraints.gridx = 1;
		constraints.weightx = 0.0;
		constraints.fill = GridBagConstraints.NONE;
		constraints.insets = new Insets(0, 0, 6, 0);
		pianoVolumeSpinner.setPreferredSize(new Dimension(58, pianoVolumeSpinner.getPreferredSize().height));
		panel.add(pianoVolumeSpinner, constraints);

		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.gridwidth = 2;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new Insets(0, 0, 0, 0);
		panel.add(pianoVolumeValue, constraints);
		return panel;
	}

	private JPanel opacityControlPanel()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);

		opacityValue.setForeground(ColorScheme.TEXT_COLOR);

		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = 0;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets(0, 0, 6, 8);
		panel.add(opacitySlider, constraints);

		constraints.gridx = 1;
		constraints.weightx = 0.0;
		constraints.fill = GridBagConstraints.NONE;
		constraints.insets = new Insets(0, 0, 6, 0);
		opacitySpinner.setPreferredSize(new Dimension(58, opacitySpinner.getPreferredSize().height));
		panel.add(opacitySpinner, constraints);

		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.gridwidth = 2;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new Insets(0, 0, 0, 0);
		panel.add(opacityValue, constraints);
		return panel;
	}

	private JPanel targetSizeControlPanel()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);

		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = 0;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets(0, 0, 6, 8);
		panel.add(targetSizeSlider, constraints);

		constraints.gridx = 1;
		constraints.weightx = 0.0;
		constraints.fill = GridBagConstraints.NONE;
		constraints.insets = new Insets(0, 0, 6, 0);
		targetSizeSpinner.setPreferredSize(new Dimension(58, targetSizeSpinner.getPreferredSize().height));
		panel.add(targetSizeSpinner, constraints);

		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.gridwidth = 2;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new Insets(0, 0, 0, 0);
		panel.add(targetSizeValue, constraints);
		return panel;
	}

	private void setOpacity(int opacity)
	{
		int clamped = clampOpacity(opacity);
		updating = true;
		try
		{
			opacitySlider.setValue(clamped);
			opacitySpinner.setValue(clamped);
			updateOpacityValue(clamped);
		}
		finally
		{
			updating = false;
		}
		configManager.setConfiguration(
			NhAimTrainerConfig.GROUP,
			NhAimTrainerConfig.BOX_OPACITY,
			clamped);
	}

	private void setTargetSize(int size)
	{
		int clamped = clampTargetSize(size);
		updating = true;
		try
		{
			targetSizeSlider.setValue(clamped);
			targetSizeSpinner.setValue(clamped);
			updateTargetSizeValue(clamped);
		}
		finally
		{
			updating = false;
		}
		configManager.setConfiguration(
			NhAimTrainerConfig.GROUP,
			NhAimTrainerConfig.GAME_TARGET_AVERAGE_SIZE,
			clamped);
	}

	private void setPianoVolume(int volume)
	{
		int clamped = clampPianoVolume(volume);
		updating = true;
		try
		{
			pianoVolumeSlider.setValue(clamped);
			pianoVolumeSpinner.setValue(clamped);
			updatePianoVolumeValue(clamped);
		}
		finally
		{
			updating = false;
		}
		configManager.setConfiguration(
			NhAimTrainerConfig.GROUP,
			NhAimTrainerConfig.PIANO_TILE_AUDIO_VOLUME,
			clamped);
	}

	private void updateOpacityValue(int opacity)
	{
		opacityValue.setText("Box opacity: " + opacity + "%");
	}

	private void updateTargetSizeValue(int size)
	{
		targetSizeValue.setText("Game target size: " + size + " px");
	}

	private void updatePianoVolumeValue(int volume)
	{
		pianoVolumeValue.setText(volume == 0 ? "Piano volume: muted" : "Piano volume: " + volume + "%");
	}

	private int clampOpacity(int opacity)
	{
		return Math.max(40, Math.min(95, opacity));
	}

	private int clampTargetSize(int size)
	{
		return Math.max(14, Math.min(96, size));
	}

	private int clampPianoVolume(int volume)
	{
		return Math.max(0, Math.min(100, volume));
	}

	private void showLeaderboard()
	{
		JTextArea textArea = new JTextArea(pianoLeaderboardSupplier.get(), 12, 28);
		textArea.setEditable(false);
		textArea.setLineWrap(false);
		textArea.setWrapStyleWord(false);
		JOptionPane.showMessageDialog(
			this,
			new JScrollPane(textArea),
			"Local Piano Tiles Scores",
			JOptionPane.PLAIN_MESSAGE);
	}
}
