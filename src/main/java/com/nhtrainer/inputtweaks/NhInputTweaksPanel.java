package com.nhtrainer.inputtweaks;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

class NhInputTweaksPanel extends PluginPanel
{
	private final ConfigManager configManager;
	private final NhInputTweaksConfig config;
	private final JCheckBox fastTabsCheckBox = new JCheckBox("Fast F-key tabs");
	private final JSlider itemDarkeningSlider = new JSlider(0, 100);
	private final JSpinner itemDarkeningSpinner = new JSpinner(new SpinnerNumberModel(35, 0, 100, 1));
	private final JLabel itemDarkeningValue = new JLabel();
	private boolean updating;

	NhInputTweaksPanel(ConfigManager configManager, NhInputTweaksConfig config)
	{
		this.configManager = configManager;
		this.config = config;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(12, 10, 10, 10));
		add(content, BorderLayout.NORTH);

		JLabel title = new JLabel("NH Input Tweaks", SwingConstants.LEFT);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.setForeground(ColorScheme.TEXT_COLOR);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16.0f));
		content.add(title);
		content.add(Box.createVerticalStrut(12));

		content.add(settingPanel("Input", fastTabsCheckBox));
		content.add(Box.createVerticalStrut(10));
		content.add(settingPanel("Item darkening", darkeningControlPanel()));

		fastTabsCheckBox.setOpaque(false);
		fastTabsCheckBox.setForeground(ColorScheme.TEXT_COLOR);
		fastTabsCheckBox.addActionListener(event ->
		{
			if (!updating)
			{
				configManager.setConfiguration(
					NhInputTweaksConfig.GROUP,
					NhInputTweaksConfig.FAST_TABS_ENABLED,
					fastTabsCheckBox.isSelected());
			}
		});

		itemDarkeningSlider.setOpaque(false);
		itemDarkeningSlider.setMajorTickSpacing(25);
		itemDarkeningSlider.setPaintTicks(true);
		itemDarkeningSlider.addChangeListener(event ->
		{
			if (!updating)
			{
				setItemDarkeningAmount(itemDarkeningSlider.getValue());
			}
		});

		itemDarkeningSpinner.addChangeListener(event ->
		{
			if (!updating)
			{
				setItemDarkeningAmount((Integer) itemDarkeningSpinner.getValue());
			}
		});

		refresh();
	}

	void refresh()
	{
		updating = true;
		try
		{
			fastTabsCheckBox.setSelected(config.fastTabsEnabled());
			int amount = clampDarkeningAmount(config.itemDarkeningAmount());
			itemDarkeningSlider.setValue(amount);
			itemDarkeningSpinner.setValue(amount);
			updateItemDarkeningValue(amount);
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

	private JPanel darkeningControlPanel()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);

		itemDarkeningValue.setForeground(ColorScheme.TEXT_COLOR);

		JLabel hint = new JLabel("0 disables item click feedback");
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setFont(hint.getFont().deriveFont(11.0f));

		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = 0;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets(0, 0, 6, 8);
		panel.add(itemDarkeningSlider, constraints);

		constraints.gridx = 1;
		constraints.weightx = 0.0;
		constraints.fill = GridBagConstraints.NONE;
		constraints.insets = new Insets(0, 0, 6, 0);
		itemDarkeningSpinner.setPreferredSize(new Dimension(58, itemDarkeningSpinner.getPreferredSize().height));
		panel.add(itemDarkeningSpinner, constraints);

		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.gridwidth = 2;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new Insets(0, 0, 2, 0);
		panel.add(itemDarkeningValue, constraints);

		constraints.gridy = 2;
		panel.add(hint, constraints);
		return panel;
	}

	private void setItemDarkeningAmount(int amount)
	{
		int clamped = clampDarkeningAmount(amount);
		updating = true;
		try
		{
			itemDarkeningSlider.setValue(clamped);
			itemDarkeningSpinner.setValue(clamped);
			updateItemDarkeningValue(clamped);
		}
		finally
		{
			updating = false;
		}
		configManager.setConfiguration(
			NhInputTweaksConfig.GROUP,
			NhInputTweaksConfig.ITEM_DARKENING_AMOUNT,
			clamped);
	}

	private void updateItemDarkeningValue(int amount)
	{
		itemDarkeningValue.setText(amount == 0 ? "Item darkening: off" : "Item darkening: " + amount);
	}

	private int clampDarkeningAmount(int amount)
	{
		return Math.max(0, Math.min(100, amount));
	}
}
