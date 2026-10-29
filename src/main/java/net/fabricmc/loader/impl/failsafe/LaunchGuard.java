/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.failsafe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import com.ganichgg.argentum.core.snapshot.ModSnapshots;
import com.ganichgg.argentum.core.crash.ArgentumWindow;
import com.ganichgg.argentum.core.crash.CrashExplainer;
import com.ganichgg.argentum.core.crash.CrashAnalysis;
import com.ganichgg.argentum.core.files.PendingRemovals;
import com.ganichgg.argentum.core.i18n.Lang;
import com.ganichgg.argentum.core.modrinth.CrashDiagnoser;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.ArgentumVersion;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.profile.Diagnostics;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Сторож запуска (клиент):
 * <ul>
 * <li>считает запуски, не дошедшие до главного меню; после {@value #LOOP_THRESHOLD} подряд до поиска модов предлагает
 * вернуть прошлый рабочий набор, отключить вероятного виновника или запуститься без модов один раз;</li>
 * <li>при удачном запуске снимает рабочий набор модов ({@link ModSnapshots});</li>
 * <li>когда игра падает (появился новый отчёт о падении), перед выходом показывает окно Argentum: что упало простыми
 * словами, какой мод вероятно виноват и что нажать.</li>
 * </ul>
 * Скрытая фоновая копия игры (бесшовное применение модов) окон не показывает и падения не считает.
 */
public final class LaunchGuard {
	static final int LOOP_THRESHOLD = 2;
	private static final String STATE_FILE = "launch-state.properties";
	private static final String ISSUES_URL = "https://github.com/GanichGG/Argentum/issues/new";

	private static volatile Path gameDir;
	private static volatile Path modsDir;
	private static volatile boolean safeMode;
	private static volatile boolean titleSeen;

	private LaunchGuard() { }

	/** Этот запуск — «без модов, один раз» после цикла падений: моды из папки mods не загружаются. */
	public static boolean safeMode() {
		return safeMode;
	}

	static boolean enabled() {
		return !"false".equalsIgnoreCase(System.getProperty("argentum.failsafe"))
				&& FabricLoaderImpl.INSTANCE.getEnvironmentType() == EnvType.CLIENT;
	}

	/** Скрытая копия игры, которую ещё не показали игроку (признаки ставит клиентский мод). */
	static boolean hiddenChild() {
		return System.getProperty("argentum.handoff") != null && !Boolean.getBoolean("argentum.handoff.adopted");
	}

	// ---------------------------------------------------------------- запуск

	/** Из загрузчика до поиска модов. */
	public static void beforeDiscovery(Path game, Path mods) {
		if (!enabled()) {
			return;
		}

		gameDir = game;
		modsDir = mods;

		try {
			String rolled = ModSnapshots.applyIfRequested(game, mods);

			if (System.getProperty("argentum.handoff") != null) {
				return; // фоновая копия: ни счёта падений, ни окон
			}

			Properties st = readState(game);
			String status = st.getProperty("status", "");
			int prev = parseInt(st.getProperty("failed"));
			int failed = status.equals("starting") ? prev + 1 : status.equals("safe") ? prev : 0;

			writeState(game, "starting", failed);
			installCrashHook();

			if (failed >= LOOP_THRESHOLD && rolled == null) {
				loopDialog(game, mods, failed);
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.GENERAL, "Сторож запуска: %s", t); // никогда не мешаем игре запуститься
		}
	}

	/** Игра дошла до главного меню. */
	public static void titleReached() {
		Path game = gameDir;
		Path mods = modsDir;

		if (game == null || titleSeen) {
			return;
		}

		titleSeen = true;

		if (System.getProperty("argentum.handoff") == null) {
			writeState(game, safeMode ? "safe" : "running", safeMode ? parseInt(readState(game).getProperty("failed")) : 0);
		}

		if (safeMode) {
			return; // набор без модов — не рабочий набор игрока
		}

		Thread t = new Thread(() -> ModSnapshots.take(game, mods), "argentum-snapshot");
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private static void loopDialog(Path game, Path mods, int failed) {
		ModSnapshots.Snapshot target = ModSnapshots.rollbackTarget(game, mods);
		Properties p = base();
		p.setProperty("heading", Lang.tr("Игра несколько раз подряд не смогла запуститься"));

		StringBuilder text = new StringBuilder(String.format(Lang.tr("Неудачных запусков подряд: %d. Похоже, мешает последнее изменение модов."), failed));

		if (target != null) {
			ModSnapshots.Diff d = ModSnapshots.diff(target, mods);

			if (!d.isEmpty()) {
				text.append("\n\n").append(String.format(Lang.tr("С последнего удачного запуска (%s):"), target.date()));
				appendList(text, Lang.tr("добавлены"), d.added);
				appendList(text, Lang.tr("изменены"), d.changed);
				appendList(text, Lang.tr("убраны"), d.removed);
			}
		}

		p.setProperty("explanation", text.toString());

		CrashDiagnoser.Suspect suspect = null;
		CrashDiagnoser.Diagnosis diag = CrashDiagnoser.diagnose(game, mods);

		if (diag != null && !diag.suspects.isEmpty()) {
			suspect = diag.suspects.get(0);
			p.setProperty("suspectLabel", Lang.tr("Вероятная причина:"));
			p.setProperty("suspect", suspect.name + " (" + suspect.fileName + ")");
			p.setProperty("suspectWhy", suspect.reason);
		}

		p.setProperty("actionLabel", Lang.tr("Что сделать:"));
		p.setProperty("action", target != null ? Lang.tr("Вернуть набор модов, с которым игра запускалась, — это отменит последние изменения.")
				: Lang.tr("Запусти игру без модов один раз, чтобы проверить, что дело в них."));

		int i = 0;

		if (target != null) {
			p.setProperty("main." + i++, "loopRollback|" + String.format(Lang.tr("Вернуть набор от %s"), target.date()));
		}

		if (suspect != null && Files.isRegularFile(mods.resolve(suspect.fileName))) {
			p.setProperty("main." + i++, "loopDisable|" + String.format(Lang.tr("Отключить «%s»"), suspect.name));
		}

		p.setProperty("main." + i, "safe|" + Lang.tr("Запустить без модов (один раз)"));
		p.setProperty("bottom.0", "continue|" + Lang.tr("Запустить как есть"));

		String choice = WindowLauncher.show(p);
		Log.info(LogCategory.GENERAL, "Цикл падений: выбрано «%s»", choice);

		switch (choice) {
		case "loopRollback":
			if (target != null) {
				ModSnapshots.restore(game, mods, target);
			}

			writeState(game, "starting", 0);
			break;
		case "loopDisable":
			disableNow(mods, suspect.fileName);
			writeState(game, "starting", 0);
			break;
		case "safe":
			safeMode = true;
			Log.warn(LogCategory.GENERAL, "Безопасный запуск: моды из папки mods в этот раз не загружаются");
			break;
		default:
			writeState(game, "starting", 0);
		}
	}

	private static void appendList(StringBuilder sb, String what, List<String> files) {
		if (files.isEmpty()) {
			return;
		}

		sb.append('\n').append("• ").append(what).append(": ");
		int n = Math.min(5, files.size());
		sb.append(String.join(", ", files.subList(0, n)));

		if (files.size() > n) {
			sb.append(String.format(Lang.tr(" и ещё %d"), files.size() - n));
		}
	}

	private static void disableNow(Path mods, String file) {
		try {
			Files.move(mods.resolve(file), mods.resolve(file + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try {
				PendingRemovals.scheduleDisable(mods, file);
			} catch (IOException ignored) {
				// не удалось — игрок увидит окно снова
			}
		}
	}

	// ---------------------------------------------------------------- падение

	private static void installCrashHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				onExit();
			} catch (Throwable t) {
				Log.warn(LogCategory.GENERAL, "Окно ошибки не показано: %s", t);
			}
		}, "argentum-crash-window"));
	}

	private static void onExit() throws IOException {
		Path game = gameDir;

		if (game == null || hiddenChild()) {
			return;
		}

		Path report = newCrashReport(game);

		if (report == null) {
			return;
		}

		String text = readLimited(report, 400_000, false);
		String log = readLimited(game.resolve("logs").resolve("latest.log"), 2_000_000, true);
		CrashAnalysis a = CrashAnalysis.analyze(text, log);
		List<CrashDiagnoser.Suspect> suspects = CrashDiagnoser.analyzeReport(text, modsDir);
		CrashDiagnoser.Suspect top = pick(suspects, a);
		String mc = FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
		CrashExplainer ex = CrashExplainer.explain(a, top, mc);
		String summary = Diagnostics.clean(ex.summary(a, ArgentumVersion.DISPLAY, mc));
		Path zip = null;

		try {
			zip = Diagnostics.create(game, summary + "\n" + details(a));
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Пакет диагностики не создан: %s", e);
		}

		CrashDiagnoser.markHandled(game, report.getFileName().toString());

		Properties p = base();
		p.setProperty("heading", ex.heading);
		p.setProperty("explanation", ex.explanation);
		p.setProperty("actionLabel", Lang.tr("Что сделать:"));
		p.setProperty("action", ex.action);
		p.setProperty("crashFile", report.toAbsolutePath().toString());
		p.setProperty("summary", summary);
		p.setProperty("details", Diagnostics.clean(details(a)));
		int i = 0;

		if (top != null) {
			p.setProperty("suspectLabel", Lang.tr("Вероятная причина:"));
			p.setProperty("suspect", top.name + " (" + top.fileName + ")");
			p.setProperty("suspectWhy", top.reason);
			p.setProperty("suspectFile", top.fileName);

			if (Files.isRegularFile(modsDir.resolve(top.fileName)) && !PendingRemovals.isPending(top.fileName)) {
				p.setProperty("main." + i++, "disable|" + String.format(Lang.tr("Отключить «%s»"), top.name));
				p.setProperty("disabledDone", String.format(Lang.tr("Готово: «%s» будет отключён. Запусти игру снова."), top.name));
			}
		}

		ModSnapshots.Snapshot target = ModSnapshots.rollbackTarget(game, modsDir);

		if (target != null) {
			p.setProperty("main." + i++, "rollback|" + Lang.tr("Вернуть прошлый рабочий набор"));
			p.setProperty("rollbackDone", String.format(Lang.tr("Готово: при следующем запуске вернётся набор модов от %s. Запусти игру снова."), target.date()));
		}

		int e = 0;

		if (zip != null) {
			p.setProperty("diagnosticsZip", zip.toAbsolutePath().toString());
			p.setProperty("extra." + e++, "diagnostics|" + Lang.tr("Показать пакет диагностики"));
		}

		p.setProperty("extra." + e++, "copy|" + Lang.tr("Скопировать для форума"));
		p.setProperty("issueUrl", issueUrl(ex.heading, summary));
		p.setProperty("extra." + e, "issue|" + Lang.tr("Сообщить автору"));
		p.setProperty("bottom.0", "report|" + Lang.tr("Открыть отчёт"));
		p.setProperty("bottom.1", "close|" + Lang.tr("Закрыть"));

		WindowLauncher.show(p);
	}

	/** Вероятный виновник: если ошибка Mixin назвала мод — он, иначе самый «набравший» по отчёту. */
	private static CrashDiagnoser.Suspect pick(List<CrashDiagnoser.Suspect> suspects, CrashAnalysis a) {
		if (suspects.isEmpty()) {
			return null;
		}

		if (a.mixinMod != null) {
			for (CrashDiagnoser.Suspect s : suspects) {
				if (a.mixinMod.equals(s.modId)) {
					return s;
				}
			}
		}

		return suspects.get(0);
	}

	static String details(CrashAnalysis a) {
		StringBuilder sb = new StringBuilder();

		if (a.root != null) {
			sb.append(Lang.tr("Первопричина:")).append('\n').append(a.root).append('\n');

			for (int i = 0; i < Math.min(8, a.root.frames.size()); i++) {
				sb.append("  at ").append(a.root.frames.get(i)).append('\n');
			}
		}

		if (a.chain.size() > 1) {
			sb.append('\n').append(Lang.tr("Цепочка ошибок (сверху вниз):")).append('\n');

			for (CrashAnalysis.Cause c : a.chain) {
				String line = c.toString();
				sb.append("• ").append(line.length() > 200 ? line.substring(0, 197) + "..." : line).append('\n');
			}
		}

		if (a.firstLogError != null) {
			sb.append('\n').append(Lang.tr("Первая ошибка в журнале (с неё, вероятно, всё началось):")).append('\n').append(a.firstLogError).append('\n');
		}

		return sb.toString();
	}

	private static String issueUrl(String title, String summary) {
		String body = summary + "\n" + Lang.tr("(Приложи к сообщению пакет диагностики — кнопка «Показать пакет диагностики».)");

		if (body.length() > 1500) {
			body = body.substring(0, 1500);
		}

		try {
			return ISSUES_URL + "?title=" + URLEncoder.encode(title, "UTF-8") + "&body=" + URLEncoder.encode(body, "UTF-8");
		} catch (IOException e) {
			return ISSUES_URL;
		}
	}

	/** Отчёт о падении, появившийся за этот запуск (и ещё не показанный); иначе null. */
	private static Path newCrashReport(Path game) throws IOException {
		Path dir = game.resolve("crash-reports");

		if (!Files.isDirectory(dir)) {
			return null;
		}

		long since = ManagementFactory.getRuntimeMXBean().getStartTime() - 2000;
		Path newest = null;
		long newestTime = since;

		try (Stream<Path> files = Files.list(dir)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				long t = Files.getLastModifiedTime(p).toMillis();

				if (p.getFileName().toString().endsWith(".txt") && t >= newestTime) {
					newest = p;
					newestTime = t;
				}
			}
		}

		return newest;
	}

	private static String readLimited(Path file, int max, boolean tail) {
		if (!Files.isRegularFile(file)) {
			return null;
		}

		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			long len = raf.length();
			int n = (int) Math.min(max, len);
			raf.seek(tail ? len - n : 0);
			byte[] buf = new byte[n];
			raf.readFully(buf);

			return new String(buf, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- окно и состояние

	private static Properties base() {
		Properties p = new Properties();
		p.setProperty("title", "Argentum");
		p.setProperty("gameDir", gameDir.toAbsolutePath().toString());
		p.setProperty("modsDir", modsDir.toAbsolutePath().toString());
		p.setProperty("detailsShow", Lang.tr("Подробности"));
		p.setProperty("detailsHide", Lang.tr("Скрыть подробности"));
		p.setProperty("copied", Lang.tr("Скопировано — вставь в сообщение на форуме или автору."));
		p.setProperty("failed", Lang.tr("Не получилось:"));

		return p;
	}

	private static Properties readState(Path game) {
		Properties p = new Properties();
		Path f = game.resolve(".argentum").resolve(STATE_FILE);

		if (Files.isRegularFile(f)) {
			try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException ignored) {
				// пустое состояние
			}
		}

		return p;
	}

	private static void writeState(Path game, String status, int failed) {
		Properties p = new Properties();
		p.setProperty("status", status);
		p.setProperty("failed", Integer.toString(failed));
		p.setProperty("time", Long.toString(System.currentTimeMillis()));

		try {
			Path f = game.resolve(".argentum").resolve(STATE_FILE);
			Files.createDirectories(f.getParent());

			try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
				p.store(w, "Argentum launch guard");
			}
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось записать состояние запуска: %s", e);
		}
	}

	private static int parseInt(String s) {
		try {
			return s == null ? 0 : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Запуск окна {@link ArgentumWindow} отдельным процессом Java и ожидание выбора. */
	static final class WindowLauncher {
		private WindowLauncher() { }

		static String show(Properties p) {
			// GraphicsEnvironment.isHeadless() тут не годится: Minecraft сам ставит java.awt.headless=true, хотя экран есть.
			// Окно живёт в отдельном процессе, где этого признака нет.
			if (System.getenv("CI") != null || SystemProperties.isSet(SystemProperties.NO_GUI)) {
				return "";
			}

			Path tmp = null;

			try {
				tmp = Files.createTempFile("argentum-window", ".properties");

				try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
					p.store(w, null);
				}

				Process process = new ProcessBuilder(java(), "-Xmx128M", "-Djava.awt.headless=false", "-cp", UrlUtil.LOADER_CODE_SOURCE.toString(), ArgentumWindow.class.getName(), tmp.toString())
						.redirectErrorStream(true)
						.start();
				String choice = "";

				try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;

					while ((line = r.readLine()) != null) {
						if (line.startsWith(ArgentumWindow.RESULT_PREFIX)) {
							choice = line.substring(ArgentumWindow.RESULT_PREFIX.length()).trim();
						} else if (!line.trim().isEmpty()) {
							Log.info(LogCategory.GENERAL, "[окно] %s", line);
						}
					}
				}

				process.waitFor();

				return choice;
			} catch (Exception e) {
				Log.warn(LogCategory.GENERAL, "Не удалось открыть окно Argentum: %s", e);
				return "";
			} finally {
				if (tmp != null) {
					try {
						Files.deleteIfExists(tmp);
					} catch (IOException ignored) {
						// временный файл
					}
				}
			}
		}

		private static String java() {
			Path bin = Paths.get(System.getProperty("java.home"), "bin");

			for (String exe : new String[] {"javaw.exe", "java.exe", "java"}) {
				if (Files.isRegularFile(bin.resolve(exe))) {
					return bin.resolve(exe).toString();
				}
			}

			return "java";
		}
	}
}
