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
import java.util.List;

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

	/** Вызывается при старте до поиска модов: удаляет всё, что было отложено. */
	public static void apply(Path modsDir) {
		Path list = modsDir.resolve(FILE_NAME);

		if (!Files.isRegularFile(list)) {
			return;
		}

		try {
			List<String> names = Files.readAllLines(list, StandardCharsets.UTF_8);

			for (String name : names) {
				name = name.trim();

				if (name.isEmpty() || !isPlainName(name)) {
					continue;
				}

				try {
					if (Files.deleteIfExists(modsDir.resolve(name))) {
						Log.info(LogCategory.GENERAL, "Удалён мод, отмеченный на удаление: %s", name);
					}
				} catch (IOException e) {
					Log.warn(LogCategory.GENERAL, "Не удалось удалить " + name + " (останется до следующего запуска)", e);
					Files.write(modsDir.resolve(FILE_NAME + ".keep"), (name + "\n").getBytes(StandardCharsets.UTF_8),
							StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			}

			Files.delete(list);
			Path keep = modsDir.resolve(FILE_NAME + ".keep");

			if (Files.exists(keep)) {
				Files.move(keep, list);
			}
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось обработать список отложенных удалений", e);
		}
	}

	private static boolean isPlainName(String name) {
		return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.equals(".") && !name.equals("..");
	}
}
