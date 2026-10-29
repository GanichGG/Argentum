/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ganichgg.argentum.core.CorePlatform;
import com.ganichgg.argentum.core.log.Log;
import com.ganichgg.argentum.core.mods.ModJar;

/**
 * Что гибридное ядро увидело в папке mods при этом запуске: каждый jar и его вид. Пока (этап 1, шаг 2) только смотрит и
 * пишет отчёт {@code .argentum/hybrid-report.txt} — загрузку Fabric- и Forge-модов добавят слои совместимости.
 */
final class HybridDiscovery {
	static final Logger LOGGER = LoggerFactory.getLogger("Argentum");
	private static final Map<Path, ModJar> SEEN = new LinkedHashMap<>();
	private static boolean started;

	private HybridDiscovery() { }

	/** Подключение Argentum Core к NeoForge: папка игры, версия, журнал. Один раз. */
	static synchronized void start() {
		if (started) {
			return;
		}

		started = true;
		CorePlatform.install(HybridDiscovery::gameDir, version(), "Argentum");
		Log.setSink((level, category, message, exc) -> {
			switch (level) {
			case DEBUG:
				LOGGER.debug(message, exc);
				break;
			case WARN:
				LOGGER.warn(message, exc);
				break;
			case ERROR:
				LOGGER.error(message, exc);
				break;
			default:
				LOGGER.info(message, exc);
			}
		});
		LOGGER.info("Argentum 3.0 {}: гибридное ядро подключено к NeoForge", version());
	}

	private static Path gameDir() {
		try {
			return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
		} catch (Throwable t) {
			return null;
		}
	}

	private static String version() {
		String v = HybridDiscovery.class.getPackage().getImplementationVersion();

		return v != null ? v : "dev";
	}

	/** Запомнить jar, который NeoForge предложил прочитать. */
	static synchronized void saw(Path file) {
		if (file == null || !Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".jar") || SEEN.containsKey(file)) {
			return;
		}

		SEEN.put(file, ModJar.read(file));
	}

	/** Поиск модов закончен: итог в журнал и в отчёт. */
	static synchronized void finish() {
		Map<ModJar.Kind, List<ModJar>> byKind = new EnumMap<>(ModJar.Kind.class);

		for (ModJar m : SEEN.values()) {
			byKind.computeIfAbsent(m.kind, k -> new ArrayList<>()).add(m);
		}

		LOGGER.info("Argentum 3.0: в папке mods — NeoForge: {}, Fabric: {}, Forge: {}, Quilt: {}, прочее: {}",
				count(byKind, ModJar.Kind.NEOFORGE), count(byKind, ModJar.Kind.FABRIC), count(byKind, ModJar.Kind.FORGE),
				count(byKind, ModJar.Kind.QUILT), count(byKind, ModJar.Kind.UNKNOWN));

		StringBuilder sb = new StringBuilder("Argentum 3.0 — моды, найденные при запуске\n");
		sb.append("(Fabric и Forge пока только находятся: их загрузку добавят слои совместимости)\n");

		for (ModJar.Kind kind : ModJar.Kind.values()) {
			List<ModJar> list = byKind.get(kind);

			if (list == null) {
				continue;
			}

			sb.append('\n').append(kind).append(" (").append(list.size()).append("):\n");

			for (ModJar m : list) {
				sb.append("  ").append(m.file.getFileName());

				if (!m.mods.isEmpty()) {
					sb.append("  ->  ").append(m.mods);
				}

				if (!m.alsoHas.isEmpty()) {
					sb.append("  (ещё описания: ").append(m.alsoHas).append(')');
				}

				sb.append('\n');
			}
		}

		try {
			Path report = CorePlatform.gameDir().resolve(".argentum").resolve("hybrid-report.txt");
			Files.createDirectories(report.getParent());
			Files.write(report, sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.warn("Argentum 3.0: не удалось записать отчёт о модах", e);
		}
	}

	private static int count(Map<ModJar.Kind, List<ModJar>> byKind, ModJar.Kind kind) {
		List<ModJar> l = byKind.get(kind);

		return l == null ? 0 : l.size();
	}
}
