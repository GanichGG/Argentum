/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.hotload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Отложенное удаление модов. Пока игра запущена, Windows не даёт удалить загруженный jar, поэтому мод просто
 * записывается в {@code mods/.argentum-pending-delete} и удаляется при следующем старте — ещё до поиска модов.
 */
public final class PendingRemovals {
	private static final String FILE_NAME = ".argentum-pending-delete";

	private PendingRemovals() { }

	/** Запоминает файл мода (только имя, без пути) для удаления при следующем запуске. */
	public static void schedule(Path modsDir, String fileName) throws IOException {
		if (!isPlainName(fileName)) {
			throw new IOException("недопустимое имя файла: " + fileName);
		}

		Files.createDirectories(modsDir);
		Files.write(modsDir.resolve(FILE_NAME), (fileName + "\n").getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** Файлы, которые не удалось удалить сразу (их держит другой процесс, например прежняя копия игры): не загружаем их. */
	private static final Set<String> STILL_PENDING = ConcurrentHashMap.newKeySet();

	/** Помечен ли файл на удаление — такие моды не должны загружаться. */
	public static boolean isPending(String fileName) {
		return STILL_PENDING.contains(fileName);
	}

	/**
	 * Вызывается при старте до поиска модов: удаляет всё, что было отложено. Файл, который удалить не вышло (его ещё
	 * держит предыдущая копия игры при бесшовном переключении), остаётся помеченным: он не загружается, а удаление
	 * повторяется в фоне, пока файл не освободится.
	 */
	public static void apply(Path modsDir) {
		Path list = modsDir.resolve(FILE_NAME);

		if (!Files.isRegularFile(list)) {
			return;
		}

		try {
			for (String name : Files.readAllLines(list, StandardCharsets.UTF_8)) {
				name = name.trim();

				if (!name.isEmpty() && isPlainName(name)) {
					STILL_PENDING.add(name);
				}
			}

			for (String name : new ArrayList<>(STILL_PENDING)) {
				tryDelete(modsDir, name);
			}

			rewrite(list);
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось обработать список отложенных удалений", e);
			return;
		}

		if (!STILL_PENDING.isEmpty()) {
			Thread retry = new Thread(() -> retryLoop(modsDir, list), "argentum-pending-delete");
			retry.setDaemon(true);
			retry.start();
		}
	}

	private static void retryLoop(Path modsDir, Path list) {
		for (int i = 0; i < 100 && !STILL_PENDING.isEmpty(); i++) {
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				return;
			}

			boolean changed = false;

			for (String name : new ArrayList<>(STILL_PENDING)) {
				changed |= tryDelete(modsDir, name);
			}

			if (changed) {
				try {
					rewrite(list);
				} catch (IOException e) {
					return;
				}
			}
		}
	}

	private static boolean tryDelete(Path modsDir, String name) {
		try {
			if (Files.deleteIfExists(modsDir.resolve(name))) {
				Log.info(LogCategory.GENERAL, "Удалён мод, отмеченный на удаление: %s", name);
			}

			STILL_PENDING.remove(name);

			return true;
		} catch (IOException e) {
			return false; // файл занят — попробуем позже
		}
	}

	/** Оставляет в списке только то, что ещё не удалено. */
	private static void rewrite(Path list) throws IOException {
		if (STILL_PENDING.isEmpty()) {
			Files.deleteIfExists(list);
		} else {
			Files.write(list, (String.join("\n", STILL_PENDING) + "\n").getBytes(StandardCharsets.UTF_8));
		}
	}

	private static boolean isPlainName(String name) {
		return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.equals(".") && !name.equals("..");
	}
}
