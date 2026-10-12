/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.installer.anchor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

/**
 * Окно установщика в духе установщика Fabric: версия Minecraft, версия загрузчика, куда ставить, кнопка «Установить».
 * Только стандартные компоненты в системной теме Windows, без собственного оформления. Загрузчик и каталог модов ставятся
 * всегда вместе — это один продукт, отдельного выбора нет.
 */
final class InstallerWindow {
	private final AnchorMain.InstallLocation location;
	private final String productVersion;
	private final JFrame frame = new JFrame();
	private final DefaultComboBoxModel<InstallTargets.Target> model = new DefaultComboBoxModel<>();
	private final JComboBox<InstallTargets.Target> targetBox = new JComboBox<>(model);
	private final JComboBox<String> versionBox = new JComboBox<>(AnchorMain.SUPPORTED_VERSIONS);
	private final JLabel status = new JLabel(" ");
	private final JButton installButton = new JButton();
	private final JButton browseButton = new JButton("...");

	private boolean installed;
	private Path lastInstallDir;

	private InstallerWindow(AnchorMain.InstallLocation location, String productVersion) {
		this.location = location;
		this.productVersion = productVersion;
	}

	static void open(AnchorMain.InstallLocation location) {
		Properties props;

		try {
			props = AnchorMain.loadLibraryList();
		} catch (Exception e) {
			props = new Properties();
		}

		String version = props.getProperty("loader.version", "");
		SwingUtilities.invokeLater(() -> {
			useSystemLook();
			new InstallerWindow(location, version).build();
		});
	}

	/** Системная тема Windows. Выставлять надо до создания любых компонентов — иначе они остаются в стандартной теме Swing. */
	private static void useSystemLook() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Throwable ignored) {
			// системная тема недоступна — остаётся стандартная
		}
	}

	/**
	 * Для сборки нативного exe: открывает окно, выпадающий список, диалоги и выбор папки без участия игрока, чтобы трассировщик
	 * GraalVM записал всё, что Swing создаёт через рефлексию (системная тема Windows). См. docs/RELEASING.md.
	 */
	static void trace(AnchorMain.InstallLocation location) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			useSystemLook();
			InstallerWindow w = new InstallerWindow(location, "trace");
			w.build();
			w.targetBox.showPopup();
			w.versionBox.showPopup();
			new JFileChooser().getFileSystemView().getHomeDirectory();
			new JFileChooser().getUI();
			JOptionPane pane = new JOptionPane("trace", JOptionPane.INFORMATION_MESSAGE);
			pane.createDialog(w.frame, "trace").dispose();
			new JOptionPane("trace", JOptionPane.ERROR_MESSAGE).createDialog(w.frame, "trace").dispose();
		});
		Thread.sleep(1500);
		System.exit(0);
	}

	private void build() {
		frame.setTitle(AnchorLang.tr("Установщик ArgentumLoader"));
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				exit();
			}
		});

		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(new EmptyBorder(14, 16, 14, 16));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 0, 5, 8);
		c.anchor = GridBagConstraints.WEST;

		row(panel, c, 0, AnchorLang.tr("Версия Minecraft:"), versionBox, null);

		JComboBox<String> loaderBox = new JComboBox<>(new String[] {productVersion});
		loaderBox.setEnabled(false); // загрузчик встроен в установщик — версия одна
		row(panel, c, 1, AnchorLang.tr("Версия загрузчика:"), loaderBox, null);

		browseButton.setMargin(new Insets(0, 6, 0, 6));
		row(panel, c, 2, AnchorLang.tr("Куда установить:"), targetBox, browseButton);

		c.gridx = 0;
		c.gridy = 3;
		c.gridwidth = 3;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.CENTER;
		c.insets = new Insets(12, 0, 4, 0);
		status.setHorizontalAlignment(JLabel.CENTER);
		panel.add(status, c);

		c.gridy = 4;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(4, 0, 0, 0);
		installButton.setText(AnchorLang.tr("Установить"));
		panel.add(installButton, c);

		fillTargets();
		targetBox.addActionListener(e -> updateStatus());
		browseButton.addActionListener(e -> chooseFolder());
		installButton.addActionListener(e -> onInstall());
		updateStatus();

		frame.setContentPane(panel);
		frame.pack();
		frame.setMinimumSize(new Dimension(520, frame.getHeight()));
		frame.setResizable(false);
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private static void row(JPanel panel, GridBagConstraints c, int y, String label, java.awt.Component field, java.awt.Component extra) {
		c.gridx = 0;
		c.gridy = y;
		c.gridwidth = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(5, 0, 5, 12);
		panel.add(new JLabel(label), c);

		c.gridx = 1;
		c.gridwidth = extra == null ? 2 : 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(5, 0, 5, extra == null ? 0 : 6);
		panel.add(field, c);

		if (extra != null) {
			c.gridx = 2;
			c.gridwidth = 1;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			c.insets = new Insets(5, 0, 5, 0);
			panel.add(extra, c);
		}
	}

	private void fillTargets() {
		List<InstallTargets.Target> targets = InstallTargets.detect(InstallTargets.appData());
		InstallTargets.Target preselected = null;

		// если установщик запущен прямо из папки инстанса или .minecraft — выбираем её
		InstallTargets.Target here = InstallTargets.classify(location.gameDir);

		if (here.problem.isEmpty() || here.prism) {
			for (InstallTargets.Target t : targets) {
				if (t.dir.equals(here.dir)) {
					preselected = t;
					break;
				}
			}

			if (preselected == null && here.problem.isEmpty()) {
				targets.add(0, here);
				preselected = here;
			}
		}

		for (InstallTargets.Target t : targets) {
			model.addElement(t);
		}

		if (preselected != null) {
			targetBox.setSelectedItem(preselected);
		} else if (!targets.isEmpty()) {
			targetBox.setSelectedIndex(0);
		}
	}

	private void chooseFolder() {
		Path chosen;

		try {
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(AnchorLang.tr("Папка игры или инстанса Prism"));
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

			if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
				return;
			}

			File file = chooser.getSelectedFile();
			chosen = file.toPath();
		} catch (Throwable t) {
			// выбор папки может быть недоступен — просим вставить путь
			String text = JOptionPane.showInputDialog(frame, AnchorLang.tr("Не удалось открыть выбор папки. Вставь путь к папке игры или инстанса:"));

			if (text == null || text.trim().isEmpty()) {
				return;
			}

			chosen = Paths.get(text.trim().replace("\"", ""));
		}

		InstallTargets.Target target = InstallTargets.classify(chosen);
		model.addElement(target);
		targetBox.setSelectedItem(target);
	}

	private InstallTargets.Target selectedTarget() {
		Object selected = targetBox.getSelectedItem();

		return selected instanceof InstallTargets.Target ? (InstallTargets.Target) selected : null;
	}

	private void updateStatus() {
		InstallTargets.Target t = selectedTarget();

		if (t == null) {
			status.setForeground(UIManager.getColor("Label.foreground"));
			status.setText(AnchorLang.tr("Не найден Prism или .minecraft — нажми «...» и выбери папку"));
			installButton.setEnabled(false);

			return;
		}

		if (t.prism && t.minecraftVersion != null) {
			versionBox.setSelectedItem(t.minecraftVersion);
			versionBox.setEnabled(false); // версия уже задана инстансом
		} else {
			versionBox.setEnabled(true);
		}

		targetBox.setToolTipText(t.dir.toString());

		if (!t.problem.isEmpty()) {
			status.setForeground(new Color(0xB00020));
			status.setText(t.problem);
		} else if (!t.warning.isEmpty()) {
			status.setForeground(new Color(0xA15C00));
			status.setText(t.warning);
		} else {
			status.setForeground(UIManager.getColor("Label.foreground"));
			status.setText(installed ? AnchorLang.tr("Готово!") : AnchorLang.tr("Готово к установке"));
		}

		installButton.setEnabled(t.problem.isEmpty());
	}

	private void onInstall() {
		InstallTargets.Target t = selectedTarget();

		if (t == null || !t.problem.isEmpty()) {
			return;
		}

		String mcVersion = t.minecraftVersion != null ? t.minecraftVersion : (String) versionBox.getSelectedItem();
		installButton.setEnabled(false);
		targetBox.setEnabled(false);
		browseButton.setEnabled(false);
		status.setForeground(UIManager.getColor("Label.foreground"));
		status.setText(AnchorLang.tr("Устанавливаю…"));

		Thread worker = new Thread(() -> {
			try {
				AnchorMain.InstallResult result = AnchorMain.installAt(t.dir, mcVersion, new ArrayList<>());
				SwingUtilities.invokeLater(() -> finished(t, result, null));
			} catch (Exception e) {
				e.printStackTrace();
				SwingUtilities.invokeLater(() -> finished(t, null, e));
			}
		}, "argentum-install");
		worker.start();
	}

	private void finished(InstallTargets.Target t, AnchorMain.InstallResult result, Exception error) {
		targetBox.setEnabled(true);
		browseButton.setEnabled(true);

		if (error != null) {
			updateStatus();
			JOptionPane.showMessageDialog(frame, AnchorLang.tr("<html><b>Не удалось установить ArgentumLoader</b><br><br>") + AnchorMain.escapeHtml(String.valueOf(error)) + "</html>",
					"ArgentumLoader", JOptionPane.ERROR_MESSAGE);

			return;
		}

		installed = true;
		lastInstallDir = t.dir;
		updateStatus();
		JOptionPane.showMessageDialog(frame, AnchorMain.successMessage(result), "ArgentumLoader", JOptionPane.INFORMATION_MESSAGE);
	}

	private void exit() {
		if (installed) {
			AnchorMain.scheduleCleanup(AnchorMain.cleanupList(location.leftovers, lastInstallDir));
		}

		frame.dispose();
		System.exit(0);
	}
}
