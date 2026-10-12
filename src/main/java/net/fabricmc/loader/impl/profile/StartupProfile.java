/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.profile;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.loader.impl.i18n.Lang;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Профилировщик запуска: сколько времени уходит на этапы загрузчика, на запуск каждого мода и на применение миксинов.
 * Собирает данные почти бесплатно (несколько вызовов nanoTime), отчёт пишется в {@code .argentum/startup-profile.txt}, когда
 * игра дошла до главного меню.
 */
public final class StartupProfile {
	private static final Map<String, Long> PHASES = Collections.synchronizedMap(new LinkedHashMap<>());
	private static final Map<String, Long> MOD_NANOS = new ConcurrentHashMap<>();
	private static final AtomicLong MIXIN_NANOS = new AtomicLong();
	private static final AtomicLong MIXIN_CLASSES = new AtomicLong();
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static final long JVM_START_MS = jvmStart();
	private static volatile boolean written;

	private StartupProfile() { }

	private static long jvmStart() {
		try {
			return ManagementFactory.getRuntimeMXBean().getStartTime();
		} catch (Throwable t) {
			return System.currentTimeMillis();
		}
	}

	/** Этап загрузчика: имя и длительность в наносекундах. */
	public static void phase(String name, long nanos) {
		PHASES.merge(name, nanos / 1_000_000L, Long::sum);
	}

	/** Момент от старта JVM (например, «главное меню»). */
	public static void mark(String name) {
		PHASES.putIfAbsent(Lang.tr("[с начала запуска] ") + name, System.currentTimeMillis() - JVM_START_MS);
	}

	/** Время, потраченное модом на запуск своей точки входа. */
	public static void entrypoint(String modId, String stage, long nanos) {
		MOD_NANOS.merge(modId + " (" + stage + ")", nanos / 1_000_000L, Long::sum);
	}

	/** Начало применения миксинов к классу; вложенные вызовы (миксины сами подгружают классы) не считаются дважды. */
	public static long mixinStart() {
		int[] d = DEPTH.get();

		return d[0]++ == 0 ? System.nanoTime() : 0L;
	}

	public static void mixinEnd(long start) {
		int[] d = DEPTH.get();

		if (--d[0] == 0 && start != 0L) {
			MIXIN_NANOS.addAndGet(System.nanoTime() - start);
			MIXIN_CLASSES.incrementAndGet();
		}
	}

	/** Игра дошла до главного меню: фиксируем момент и пишем отчёт (один раз за запуск). */
	public static void titleReached(Path gameDir) {
		if (!written) {
			mark(Lang.tr("главное меню"));
			writeOnce(gameDir);
		}
	}

	/** Пишет отчёт (один раз за запуск) и коротко дублирует его в журнал. */
	public static void writeOnce(Path gameDir) {
		if (written) {
			return;
		}

		written = true;
		StringBuilder sb = new StringBuilder();
		sb.append(Lang.tr("Профиль запуска ArgentumLoader\n\n"));
		sb.append(Lang.tr("Этапы (мс):\n"));

		synchronized (PHASES) {
			for (Map.Entry<String, Long> e : PHASES.entrySet()) {
				sb.append(String.format("  %-34s %6d%n", e.getKey(), e.getValue()));
			}
		}

		sb.append(String.format(Lang.tr("%nПрименение миксинов: %d мс на %d классов%n"), MIXIN_NANOS.get() / 1_000_000L, MIXIN_CLASSES.get()));
		sb.append(Lang.tr("\nЗапуск модов, самые долгие (мс):\n"));

		List<Map.Entry<String, Long>> mods = new ArrayList<>(MOD_NANOS.entrySet());
		mods.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

		for (int i = 0; i < Math.min(15, mods.size()); i++) {
			sb.append(String.format("  %-44s %6d%n", mods.get(i).getKey(), mods.get(i).getValue()));
		}

		try {
			Path file = gameDir.resolve(".argentum").resolve("startup-profile.txt");
			Files.createDirectories(file.getParent());
			Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось записать профиль запуска: %s", e);
		}

		Log.info(LogCategory.GENERAL, "Профиль запуска: миксины %d мс (%d классов), подробности — .argentum/startup-profile.txt",
				MIXIN_NANOS.get() / 1_000_000L, MIXIN_CLASSES.get());
	}
}
