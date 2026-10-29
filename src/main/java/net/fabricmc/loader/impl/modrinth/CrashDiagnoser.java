/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.loader.impl.i18n.Lang;

/**
 * Разбор последнего отчёта о падении игры: кто из модов вероятный виновник. Признаки — раздел «Suspected Mods»,
 * ошибки Mixin «from mod …» и классы модов в стеке ошибки (ближе к вершине стека — весомее).
 */
public final class CrashDiagnoser {
	private static final Pattern FROM_MOD = Pattern.compile("from mod ([A-Za-z0-9_.\\-]+)");
	private static final Pattern FRAME = Pattern.compile("^\\s*at ([A-Za-z0-9_.$]+)\\.[A-Za-z0-9_$<>]+\\(", Pattern.MULTILINE);
	private static final Pattern SUSPECT_LINE = Pattern.compile("^\\s*([^\\n(]+?)\\s*\\(([A-Za-z0-9_.\\-]+)\\)", Pattern.MULTILINE);
	private static final Set<String> NEVER = new HashSet<>(java.util.Arrays.asList("argentum-client", "minecraft", "java", "fabricloader"));

	public static final class Suspect {
		public final String fileName;
		public final String modId;
		public final String name;
		public final String reason;
		public final int score;

		Suspect(String fileName, String modId, String name, String reason, int score) {
			this.fileName = fileName;
			this.modId = modId;
			this.name = name;
			this.reason = reason;
			this.score = score;
		}
	}

	public static final class Diagnosis {
		public final String crashFile;
		public final String description;
		public final List<Suspect> suspects;

		Diagnosis(String crashFile, String description, List<Suspect> suspects) {
			this.crashFile = crashFile;
			this.description = description;
			this.suspects = suspects;
		}
	}

	private CrashDiagnoser() { }

	/** Последний ещё не разобранный отчёт о падении; null — падений нет или они уже разобраны. */
	public static Diagnosis diagnose(Path gameDir, Path modsDir) {
		Path reports = gameDir.resolve("crash-reports");

		if (!Files.isDirectory(reports)) {
			return null;
		}

		Path newest = null;

		try (Stream<Path> files = Files.list(reports)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String n = p.getFileName().toString();

				if (n.endsWith(".txt") && (newest == null || Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(newest)) > 0)) {
					newest = p;
				}
			}
		} catch (IOException e) {
			return null;
		}

		if (newest == null || newest.getFileName().toString().equals(handledName(gameDir))) {
			return null;
		}

		try {
			if (System.currentTimeMillis() - Files.getLastModifiedTime(newest).toMillis() > 7L * 24 * 3600 * 1000) {
				return null; // старое падение — не интересно
			}

			String text = new String(Files.readAllBytes(newest), StandardCharsets.UTF_8);

			if (text.length() > 400_000) {
				text = text.substring(0, 400_000);
			}

			return new Diagnosis(newest.getFileName().toString(), description(text), analyze(text, modsDir));
		} catch (IOException e) {
			return null;
		}
	}

	/** Запоминает отчёт как разобранный, чтобы не показывать его снова. */
	public static void markHandled(Path gameDir, String crashFile) {
		try {
			Path dir = gameDir.resolve(".argentum");
			Files.createDirectories(dir);
			Files.write(dir.resolve("diag-handled"), crashFile.getBytes(StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			// не критично: покажем ещё раз
		}
	}

	private static String handledName(Path gameDir) {
		try {
			return new String(Files.readAllBytes(gameDir.resolve(".argentum").resolve("diag-handled")), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			return "";
		}
	}

	private static String description(String text) {
		int i = text.indexOf("Description:");

		if (i < 0) {
			return "";
		}

		int end = text.indexOf('\n', i);

		return text.substring(i + "Description:".length(), end < 0 ? text.length() : end).trim();
	}

	/** Вероятные виновники по тексту отчёта (самый вероятный первым, не больше трёх). */
	public static List<Suspect> analyzeReport(String text, Path modsDir) {
		return analyze(text, modsDir);
	}

	/** Разбор текста отчёта против установленных (включённых) модов. */
	static List<Suspect> analyze(String text, Path modsDir) {
		List<LocalMods.Mod> mods = new ArrayList<>();

		for (LocalMods.Mod m : LocalMods.scan(modsDir)) {
			if (m.enabled && m.id != null && !NEVER.contains(m.id)) {
				mods.add(m);
			}
		}

		Map<String, Integer> score = new HashMap<>();
		Map<String, List<String>> why = new HashMap<>();

		// 1) раздел «Suspected Mods»
		int sm = text.indexOf("Suspected Mods:");

		if (sm >= 0) {
			String block = text.substring(sm + "Suspected Mods:".length(), Math.min(text.length(), sm + 1200));
			int stop = block.indexOf("\n\n");
			Matcher m = SUSPECT_LINE.matcher(stop > 0 ? block.substring(0, stop) : block);

			while (m.find()) {
				add(score, why, m.group(2), 10, Lang.tr("названа подозреваемой в отчёте об ошибке"));
			}
		}

		// 2) ошибки Mixin «from mod x»
		Matcher fm = FROM_MOD.matcher(text);

		while (fm.find()) {
			add(score, why, fm.group(1), 8, Lang.tr("ошибка применения Mixin от этого мода"));
		}

		// 3) классы модов в стеке
		Map<String, Set<String>> prefixes = new HashMap<>();

		for (LocalMods.Mod m : mods) {
			prefixes.put(m.id, packagePrefixes(modsDir, m));
		}

		// 3а) первопричина: код мода там, где ошибка на самом деле возникла, весит больше всего остального стека
		net.fabricmc.loader.impl.failsafe.CrashAnalysis analysis = net.fabricmc.loader.impl.failsafe.CrashAnalysis.analyze(text, null);
		List<String> rootFrames = analysis.rootFrames();

		for (int i = 0; i < Math.min(10, rootFrames.size()); i++) {
			String frame = rootFrames.get(i);
			Matcher handler = net.fabricmc.loader.impl.failsafe.CrashAnalysis.MIXIN_HANDLER.matcher(frame);

			if (handler.find()) {
				add(score, why, handler.group(1), i < 3 ? 12 : 6, Lang.tr("его вставка в код игры там, где возникла ошибка"));
				continue;
			}

			String path = frame.replace('.', '/');

			for (Map.Entry<String, Set<String>> e : prefixes.entrySet()) {
				if (e.getValue().stream().anyMatch(path::startsWith)) {
					add(score, why, e.getKey(), i < 3 ? 12 : 6, Lang.tr("ошибка возникла в его коде"));
					break;
				}
			}
		}

		Matcher fr = FRAME.matcher(text);
		int frameNo = 0;

		while (fr.find() && frameNo < 400) {
			frameNo++;
			String path = fr.group(1).replace('.', '/');

			if (path.startsWith("net/minecraft/") || path.startsWith("java/") || path.startsWith("jdk/") || path.startsWith("org/spongepowered/")
					|| path.startsWith("net/fabricmc/loader/") || path.startsWith("com/mojang/") || path.startsWith("org/lwjgl/")) {
				continue;
			}

			for (Map.Entry<String, Set<String>> e : prefixes.entrySet()) {
				for (String prefix : e.getValue()) {
					if (path.startsWith(prefix)) {
						add(score, why, e.getKey(), frameNo <= 15 ? 3 : 1, Lang.tr("его код в стеке ошибки"));
						break;
					}
				}
			}
		}

		List<Suspect> out = new ArrayList<>();

		for (LocalMods.Mod m : mods) {
			Integer s = score.get(m.id);

			if (s != null && s > 0) {
				out.add(new Suspect(m.fileName, m.id, m.name, String.join("; ", new java.util.LinkedHashSet<>(why.get(m.id))), s));
			}
		}

		out.sort((a, b) -> Integer.compare(b.score, a.score));

		return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
	}

	private static void add(Map<String, Integer> score, Map<String, List<String>> why, String id, int points, String reason) {
		score.merge(id, points, Integer::sum);
		why.computeIfAbsent(id, k -> new ArrayList<>()).add(reason);
	}

	/** Корневые пакеты мода (по три сегмента), по которым классы стека относят к нему. */
	private static Set<String> packagePrefixes(Path modsDir, LocalMods.Mod mod) {
		Set<String> prefixes = new HashSet<>();

		try (ZipFile zip = new ZipFile(modsDir.resolve(mod.fileName).toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements() && prefixes.size() < 60) {
				String name = entries.nextElement().getName();

				if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.startsWith("net/minecraft/")) {
					continue;
				}

				String[] parts = name.split("/");

				if (parts.length >= 4) {
					prefixes.add(parts[0] + "/" + parts[1] + "/" + parts[2] + "/");
				} else if (parts.length == 3) {
					prefixes.add(parts[0] + "/" + parts[1] + "/");
				}
			}
		} catch (IOException e) {
			return Collections.emptySet();
		}

		return prefixes;
	}
}
