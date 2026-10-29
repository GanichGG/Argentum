/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ganichgg.argentum.core.i18n.Lang;
import com.ganichgg.argentum.core.log.Log;
import com.ganichgg.argentum.core.log.LogCategory;

/**
 * Отложенные операции над jar'ами модов. Пока игра запущена, Windows не даёт ни удалить, ни переименовать загруженный
 * jar, поэтому операция записывается в {@code mods/.argentum-pending-delete} и выполняется при следующем старте — ещё
 * до поиска модов. Строка — имя файла (удалить) либо {@code disable:имя} (переименовать в {@code имя.disabled}).
 * Пока операция не выполнена, мод в новых запусках не загружается ({@link #isPending}).
 */
public final class PendingRemovals {
	private static final String FILE_NAME = ".argentum-pending-delete";
	private static final String DISABLE_PREFIX = "disable:";
	private static final String DISABLED_SUFFIX = ".disabled";

	private PendingRemovals() { }

	/** Запоминает файл мода (только имя, без пути) для удаления при следующем запуске. */
	public static void schedule(Path modsDir, String fileName) throws IOException {
		append(modsDir, fileName, fileName);
	}

	/** Запоминает файл мода для отключения (переименование в .disabled) при следующем запуске. */
	public static void scheduleDisable(Path modsDir, String fileName) throws IOException {
		append(modsDir, fileName, DISABLE_PREFIX + fileName);
	}

	private static void append(Path modsDir, String fileName, String line) throws IOException {
		if (!isPlainName(fileName)) {
			throw new IOException(Lang.tr("недопустимое имя файла: ") + fileName);
		}

		Files.createDirectories(modsDir);
		Files.write(modsDir.resolve(FILE_NAME), (line + "\n").getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		PENDING.put(fileName, !line.equals(fileName));
	}

	/** Отменяет отложенное отключение (например, мод включили обратно до перезапуска). */
	public static void cancel(Path modsDir, String fileName) {
		PENDING.remove(fileName);

		try {
			Path list = modsDir.resolve(FILE_NAME);

			if (Files.isRegularFile(list)) {
				Map<String, Boolean> remain = read(list);
				remain.remove(fileName);
				write(list, remain);
			}
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось обновить список отложенных операций", e);
		}
	}

	/** имя файла -> true, если операция «отключить», false — «удалить». */
	private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();

	/** Ждёт ли файл отложенной операции — такие моды не должны загружаться. */
	public static boolean isPending(String fileName) {
		return PENDING.containsKey(fileName);
	}

	/** Ждёт ли файл именно отключения (для отображения состояния «отключён» до перезапуска). */
	public static boolean isPendingDisable(String fileName) {
		return Boolean.TRUE.equals(PENDING.get(fileName));
	}

	private static Map<String, Boolean> read(Path list) throws IOException {
		Map<String, Boolean> ops = new LinkedHashMap<>();

		for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
			line = line.trim();
			boolean disable = line.startsWith(DISABLE_PREFIX);
			String name = disable ? line.substring(DISABLE_PREFIX.length()) : line;

			if (!name.isEmpty() && isPlainName(name)) {
				ops.put(name, disable);
			}
		}

		return ops;
	}

	private static void write(Path list, Map<String, Boolean> ops) throws IOException {
		if (ops.isEmpty()) {
			Files.deleteIfExists(list);
			return;
		}

		StringBuilder sb = new StringBuilder();

		for (Map.Entry<String, Boolean> e : ops.entrySet()) {
			sb.append(e.getValue() ? DISABLE_PREFIX : "").append(e.getKey()).append('\n');
		}

		Files.write(list, sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Вызывается при старте до поиска модов: выполняет отложенное. Операция, которая не удалась (файл ещё держит
	 * предыдущая копия игры при бесшовном переключении), остаётся: мод не загружается, а попытки повторяются в фоне.
	 */
	public static void apply(Path modsDir) {
		Path list = modsDir.resolve(FILE_NAME);

		if (!Files.isRegularFile(list)) {
			return;
		}

		try {
			PENDING.putAll(read(list));

			for (Map.Entry<String, Boolean> e : new LinkedHashMap<>(PENDING).entrySet()) {
				tryRun(modsDir, e.getKey(), e.getValue());
			}

			write(list, new LinkedHashMap<>(PENDING));
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось обработать список отложенных операций", e);
			return;
		}

		if (!PENDING.isEmpty()) {
			Thread retry = new Thread(() -> retryLoop(modsDir, list), "argentum-pending-delete");
			retry.setDaemon(true);
			retry.start();
		}
	}

	private static void retryLoop(Path modsDir, Path list) {
		for (int i = 0; i < 100 && !PENDING.isEmpty(); i++) {
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				return;
			}

			boolean changed = false;

			for (Map.Entry<String, Boolean> e : new LinkedHashMap<>(PENDING).entrySet()) {
				changed |= tryRun(modsDir, e.getKey(), e.getValue());
			}

			if (changed) {
				try {
					write(list, new LinkedHashMap<>(PENDING));
				} catch (IOException e) {
					return;
				}
			}
		}
	}

	private static boolean tryRun(Path modsDir, String name, boolean disable) {
		try {
			Path file = modsDir.resolve(name);

			if (disable) {
				if (Files.exists(file)) {
					Files.move(file, modsDir.resolve(name + DISABLED_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
					Log.info(LogCategory.GENERAL, "Мод отключён: %s", name);
				}
			} else if (Files.deleteIfExists(file)) {
				Log.info(LogCategory.GENERAL, "Удалён мод, отмеченный на удаление: %s", name);
			}

			PENDING.remove(name);

			return true;
		} catch (IOException e) {
			return false; // файл занят — попробуем позже
		}
	}

	private static boolean isPlainName(String name) {
		return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.equals(".") && !name.equals("..");
	}
}
