/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.crash;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.ganichgg.argentum.core.snapshot.ModSnapshots;
import com.ganichgg.argentum.core.files.PendingRemovals;

/**
 * Окно Argentum поверх упавшей или не запускающейся игры: простое окно на стандартных элементах Windows, как установщик.
 * Запускается отдельным процессом Java (у игры к этому моменту уже нет рабочего окна, а в процессе с GLFW Swing может
 * не открыться), получает готовые переведённые строки в файле {@code .properties} и печатает выбор игрока в stdout.
 *
 * <p>Кнопки двух видов: «выполнить и остаться» (открыть отчёт, скопировать, отключить мод) и «выбрать и закрыть»
 * (вариант запуска при цикле падений — его выполняет уже сама игра).
 */
public final class ArgentumWindow {
	public static final String RESULT_PREFIX = "argentum-choice=";

	private final Properties p;
	private final JFrame frame = new JFrame();
	private final JLabel status = new JLabel(" ");
	private final List<JButton> stateButtons = new ArrayList<>();
	private final CountDownLatch closed = new CountDownLatch(1);
	private volatile String choice = "";

	private ArgentumWindow(Properties p) {
		this.p = p;
	}

	public static void main(String[] args) throws Exception {
		Properties p = new Properties();

		try (Reader r = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
			p.load(r);
		}

		ArgentumWindow w = new ArgentumWindow(p);
		SwingUtilities.invokeAndWait(w::build);
		w.closed.await();
		System.out.println(RESULT_PREFIX + w.choice);
		System.out.flush();
		System.exit(0);
	}

	private String s(String key) {
		return p.getProperty(key, "");
	}

	private void build() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Throwable ignored) {
			// остаётся стандартная тема
		}

		frame.setTitle(s("title"));
		List<java.awt.Image> icons = new ArrayList<>();

		for (String size : new String[] {"32", "64"}) {
			java.net.URL url = ArgentumWindow.class.getResource("/argentum/icon-" + size + ".png");

			if (url != null) {
				icons.add(Toolkit.getDefaultToolkit().getImage(url));
			}
		}

		frame.setIconImages(icons);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				closed.countDown();
			}
		});

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));

		JLabel heading = new JLabel(html(s("heading"), 520));
		heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 5F));
		add(body, heading, 8);
		add(body, new JLabel(html(s("explanation"), 520)), 10);

		if (!s("suspect").isEmpty()) {
			JLabel suspect = new JLabel(htmlRaw("<b>" + esc(s("suspectLabel")) + "</b> " + esc(s("suspect")), 520));
			add(body, suspect, 2);

			if (!s("suspectWhy").isEmpty()) {
				JLabel why = new JLabel(html(s("suspectWhy"), 520));
				why.setForeground(Color.GRAY);
				add(body, why, 10);
			}
		}

		if (!s("action").isEmpty()) {
			add(body, new JLabel(htmlRaw("<b>" + esc(s("actionLabel")) + "</b> " + esc(s("action")), 520)), 12);
		}

		// основные действия
		JPanel main = row();
		addButtons(main, "main");
		add(body, main, 4);

		JPanel extra = row();
		addButtons(extra, "extra");

		if (extra.getComponentCount() > 0) {
			add(body, extra, 4);
		}

		// подробности — свёрнуты
		if (!s("details").isEmpty()) {
			JTextArea area = new JTextArea(s("details"), 8, 60);
			area.setEditable(false);
			area.setLineWrap(true);
			area.setWrapStyleWord(false);
			area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JScrollPane scroll = new JScrollPane(area);
			scroll.setVisible(false);
			scroll.setAlignmentX(JComponent.LEFT_ALIGNMENT);
			scroll.setPreferredSize(new Dimension(540, 150));

			JButton toggle = new JButton(s("detailsShow"));
			toggle.addActionListener(e -> {
				boolean show = !scroll.isVisible();
				scroll.setVisible(show);
				toggle.setText(show ? s("detailsHide") : s("detailsShow"));
				frame.pack();
			});

			JPanel tr = row();
			tr.add(toggle);
			add(body, tr, 4);
			add(body, scroll, 4);
		}

		status.setForeground(new Color(0x1B7F3B));
		status.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		add(body, status, 4);

		JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
		addButtons(bottom, "bottom");

		frame.getContentPane().setLayout(new BorderLayout());
		frame.getContentPane().add(body, BorderLayout.CENTER);
		frame.getContentPane().add(bottom, BorderLayout.SOUTH);
		frame.pack();
		frame.setMinimumSize(new Dimension(580, frame.getHeight()));
		frame.setLocationRelativeTo(null);
		frame.setAlwaysOnTop(true); // игра (лаунчер) может перекрыть окно — так оно точно будет видно
		frame.setVisible(true);
		frame.toFront();
		frame.setAlwaysOnTop(false);
	}

	private static JPanel row() {
		JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		r.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return r;
	}

	private static void add(JPanel body, JComponent c, int gapAfter) {
		c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		body.add(c);
		body.add(Box.createVerticalStrut(gapAfter));
	}

	/** Кнопки группы: {@code <group>.<n>=действие|текст}, по порядку. */
	private void addButtons(JPanel panel, String group) {
		for (int i = 0; ; i++) {
			String spec = s(group + "." + i);

			if (spec.isEmpty()) {
				return;
			}

			int bar = spec.indexOf('|');
			String action = spec.substring(0, bar);
			JButton b = new JButton(spec.substring(bar + 1));
			b.addActionListener(e -> run(action, b));

			if (panel.getComponentCount() > 0 && !(panel.getLayout() instanceof FlowLayout && ((FlowLayout) panel.getLayout()).getHgap() > 0)) {
				panel.add(Box.createHorizontalStrut(6));
			}

			panel.add(b);

			if (action.equals("disable") || action.equals("rollback")) {
				stateButtons.add(b);
			}

			if (i == 0 && group.equals("main")) {
				frame.getRootPane().setDefaultButton(b);
			}
		}
	}

	private void run(String action, JButton source) {
		try {
			switch (action) {
			case "disable":
				PendingRemovals.scheduleDisable(Paths.get(s("modsDir")), s("suspectFile"));
				done(s("disabledDone"));
				break;
			case "rollback":
				Path req = Paths.get(s("gameDir")).resolve(".argentum").resolve(ModSnapshots.REQUEST_FILE);
				Files.createDirectories(req.getParent());
				Files.write(req, "rollback".getBytes(StandardCharsets.UTF_8));
				done(s("rollbackDone"));
				break;
			case "diagnostics":
				reveal(Paths.get(s("diagnosticsZip")));
				break;
			case "report":
				open(Paths.get(s("crashFile")));
				break;
			case "copy":
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s("summary")), null);
				status.setText(s("copied"));
				break;
			case "issue":
				Desktop.getDesktop().browse(new URI(s("issueUrl")));
				break;
			case "close":
				frame.dispose();
				break;
			default: // выбор для игры: вернуть его и закрыться
				choice = action;
				frame.dispose();
			}
		} catch (Exception e) {
			status.setForeground(new Color(0xB00020));
			status.setText(html(s("failed") + " " + e.getMessage(), 520));
		}
	}

	/** Действие уже выполнено: другие кнопки, меняющие набор модов, больше не нужны. */
	private void done(String text) {
		for (JButton b : stateButtons) {
			b.setEnabled(false);
		}

		status.setText(html(text, 520));
		frame.pack();
	}

	private static void reveal(Path file) throws IOException {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			new ProcessBuilder("explorer.exe", "/select," + file.toAbsolutePath()).start();
		} else {
			Desktop.getDesktop().open(file.getParent().toFile());
		}
	}

	private static void open(Path file) throws IOException {
		Desktop.getDesktop().open(file.toFile());
	}

	private static String html(String text, int width) {
		return htmlRaw(esc(text), width);
	}

	private static String htmlRaw(String escaped, int width) {
		return "<html><body style='width:" + width + "px'>" + escaped.replace("\n", "<br>") + "</body></html>";
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
