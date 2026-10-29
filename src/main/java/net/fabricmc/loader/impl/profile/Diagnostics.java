/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.ArgentumVersion;
import net.fabricmc.loader.impl.failsafe.FailSafe;
import net.fabricmc.loader.impl.i18n.Lang;
import net.fabricmc.loader.impl.hotload.PendingRemovals;

/**
 * Пакет диагностики для сообщений об ошибках: версии, список модов, профиль запуска, хвост журнала и последний отчёт о падении.
 * Создаётся только на диске и никуда не отправляется; имя пользователя в путях заменяется.
 */
public final class Diagnostics {
	private static final int LOG_TAIL_LINES = 3000;
	private static final int CRASH_LINES = 400;

	private Diagnostics() { }

	/** @return созданный zip в {@code <папка игры>/.argentum/diagnostics} */
	public static Path create(Path gameDir) throws IOException {
		return create(gameDir, null);
	}

	/**
	 * @param analysis разбор падения простыми словами (из окна ошибки) или null
	 * @return созданный zip в {@code <папка игры>/.argentum/diagnostics}
	 */
	public static Path create(Path gameDir, String analysis) throws IOException {
		Path dir = gameDir.resolve(".argentum").resolve("diagnostics");
		Files.createDirectories(dir);
		Path zip = dir.resolve("argentum-diagnostics-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");

		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			add(out, "README.txt", readme());

			if (analysis != null && !analysis.isEmpty()) {
				add(out, "analysis.txt", analysis);
			}

			add(out, "info.txt", info(gameDir));
			add(out, "mod-sets.txt", modSets(gameDir));
			addFile(out, "launch-state.txt", gameDir.resolve(".argentum").resolve("launch-state.properties"), Integer.MAX_VALUE);
			add(out, "mods.txt", mods(gameDir));
			addFile(out, "startup-profile.txt", gameDir.resolve(".argentum").resolve("startup-profile.txt"), Integer.MAX_VALUE);
			addFile(out, "preflight-report.txt", gameDir.resolve(".argentum").resolve("preflight-report.txt"), Integer.MAX_VALUE);
			addFile(out, "latest.log", gameDir.resolve("logs").resolve("latest.log"), -LOG_TAIL_LINES);
			Path crash = latestCrash(gameDir);

			if (crash != null) {
				addFile(out, "crash-report.txt", crash, CRASH_LINES);
			}
		}

		return zip;
	}

	private static String readme() {
		// одной строкой: перевод ищется по всему тексту целиком
		return Lang.tr("Пакет диагностики Argentum.\n\nЧто внутри: версии игры и Java, список модов, разбор последнего падения, хвост журнала и отчёт о падении.\nЛичное убрано: имя пользователя и домашняя папка в путях заменены на <user> и <home>.\n\nКак отправить: приложи этот zip к сообщению на форуме, в Discord или автору Argentum (https://github.com/GanichGG/Argentum/issues). Сам пакет никуда не отправляется.\n");
	}

	/** Снимки рабочих наборов и чем текущий отличается от последнего удачного. */
	private static String modSets(Path gameDir) {
		StringBuilder sb = new StringBuilder();
		Path mods = gameDir.resolve("mods");
		List<net.fabricmc.loader.impl.failsafe.ModSnapshots.Snapshot> snaps = net.fabricmc.loader.impl.failsafe.ModSnapshots.list(gameDir);

		if (snaps.isEmpty()) {
			return "No working mod sets saved yet.\n";
		}

		for (net.fabricmc.loader.impl.failsafe.ModSnapshots.Snapshot s : snaps) {
			sb.append("Working set ").append(s.date()).append(": ").append(s.entries.size()).append(" files\n");
		}

		net.fabricmc.loader.impl.failsafe.ModSnapshots.Diff d = net.fabricmc.loader.impl.failsafe.ModSnapshots.diff(snaps.get(0), mods);
		sb.append("\nChanges since the last working set:\n");
		sb.append("  added: ").append(d.added).append('\n');
		sb.append("  changed: ").append(d.changed).append('\n');
		sb.append("  removed: ").append(d.removed).append('\n');

		return sb.toString();
	}

	private static String info(Path gameDir) {
		StringBuilder sb = new StringBuilder();
		FabricLoader loader = FabricLoader.getInstance();
		sb.append("ArgentumLoader: ").append(ArgentumVersion.DISPLAY).append('\n');
		sb.append("Fabric Loader base: ").append(loader.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
		sb.append("Minecraft: ").append(loader.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
		sb.append("Language: ").append(Lang.language()).append('\n');
		sb.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
		sb.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
		sb.append("Max heap: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n");
		sb.append("Processors: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
		sb.append("Environment: ").append(loader.getEnvironmentType()).append('\n');
		sb.append("Created: ").append(LocalDateTime.now()).append('\n');

		return sb.toString();
	}

	private static String mods(Path gameDir) {
		StringBuilder sb = new StringBuilder("Loaded mods:\n");
		List<String> lines = new ArrayList<>();

		for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
			ModMetadata m = c.getMetadata();
			String where = c.getOrigin().getKind().name();

			if (c.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH && !c.getOrigin().getPaths().isEmpty()) {
				where = c.getOrigin().getPaths().get(0).getFileName().toString();
			}

			lines.add(String.format("  %-40s %-24s %s", m.getId(), m.getVersion().getFriendlyString(), where));
		}

		lines.sort(Comparator.naturalOrder());

		for (String l : lines) {
			sb.append(l).append('\n');
		}

		sb.append("\nFiles in mods:\n");

		try (Stream<Path> files = Files.list(gameDir.resolve("mods"))) {
			for (String name : files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList())) {
				String note = PendingRemovals.isPendingDisable(name) ? "  (will be disabled)" : PendingRemovals.isPending(name) ? "  (will be removed)" : "";
				sb.append("  ").append(name).append(note).append('\n');
			}
		} catch (IOException e) {
			sb.append("  (cannot list: ").append(e.getMessage()).append(")\n");
		}

		List<FailSafe.Failure> failed = FailSafe.failures();

		if (!failed.isEmpty()) {
			sb.append("\nDisabled because they failed to start:\n");

			for (FailSafe.Failure f : failed) {
				sb.append("  ").append(f.modId).append(" (").append(f.stage).append("): ").append(f.reason).append('\n');
			}
		}

		return sb.toString();
	}

	private static Path latestCrash(Path gameDir) {
		Path dir = gameDir.resolve("crash-reports");

		if (!Files.isDirectory(dir)) {
			return null;
		}

		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".txt")).max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	/** limit &gt; 0: первые limit строк; limit &lt; 0: последние -limit строк. */
	private static void addFile(ZipOutputStream out, String name, Path file, int limit) throws IOException {
		if (!Files.isRegularFile(file)) {
			return;
		}

		// не readAllLines: журнал бывает не в UTF-8, и тогда он бросил бы исключение вместо того, чтобы попасть в пакет
		List<String> lines = java.util.Arrays.asList(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\r?\n", -1));
		List<String> part;

		if (limit < 0 && lines.size() > -limit) {
			part = lines.subList(lines.size() + limit, lines.size());
		} else if (limit > 0 && lines.size() > limit) {
			part = lines.subList(0, limit);
		} else {
			part = lines;
		}

		add(out, name, String.join("\n", part));
	}

	private static void add(ZipOutputStream out, String name, String text) throws IOException {
		out.putNextEntry(new ZipEntry(name));
		out.write(clean(text).getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}

	/** Убирает из текста личное: домашнюю папку и имя пользователя в путях. */
	public static String clean(String text) {
		String home = System.getProperty("user.home", "");
		String user = System.getProperty("user.name", "");

		if (!home.isEmpty()) {
			text = text.replace(home, "<home>").replace(home.replace('\\', '/'), "<home>").replace(home.replace("\\", "\\\\"), "<home>");
		}

		if (user.length() > 2) {
			text = text.replace("\\" + user + "\\", "\\<user>\\").replace("/" + user + "/", "/<user>/");
		}

		return text;
	}
}
