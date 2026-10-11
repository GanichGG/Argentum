/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Небольшой локальный реестр модов, поставленных через {@link ModrinthClient}/{@link
 * DependencyResolver} — хранится прямо в папке mods как {@value #FILE_NAME}. Нужен для
 * двух вещей: не тащить по новой уже стоящую зависимость при пересечении графов разных
 * модов, и (в будущем) для экрана со списком/обновлением/удалением модов.
 *
 * <p>Формата ради простоты — плоский JSON-объект {@code projectId -> {versionId,
 * fileName}}, пишется вручную (в проекте нет JSON-писателя, только потоковый читатель).
 */
public final class InstalledModsIndex {
	private static final String FILE_NAME = ".argentum-modrinth.json";

	public static final class Entry {
		public final String versionId;
		public final String fileName;

		public Entry(String versionId, String fileName) {
			this.versionId = versionId;
			this.fileName = fileName;
		}
	}

	private final Path file;
	private final Map<String, Entry> entries;

	private InstalledModsIndex(Path file, Map<String, Entry> entries) {
		this.file = file;
		this.entries = entries;
	}

	public static InstalledModsIndex load(Path modsDir) {
		Path file = modsDir.resolve(FILE_NAME);
		Map<String, Entry> entries = new LinkedHashMap<>();

		if (Files.isRegularFile(file)) {
			try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
				reader.beginObject();

				while (reader.hasNext()) {
					String projectId = reader.nextName();
					String versionId = null;
					String fileName = null;

					reader.beginObject();

					while (reader.hasNext()) {
						switch (reader.nextName()) {
						case "versionId": versionId = reader.nextString(); break;
						case "fileName": fileName = reader.nextString(); break;
						default: reader.skipValue();
						}
					}

					reader.endObject();

					if (versionId != null && fileName != null) {
						entries.put(projectId, new Entry(versionId, fileName));
					}
				}

				reader.endObject();
			} catch (IOException e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось прочитать %s, считаю реестр пустым: %s", FILE_NAME, e);
			}
		}

		return new InstalledModsIndex(file, entries);
	}

	public boolean contains(String projectId) {
		return entries.containsKey(projectId);
	}

	public void put(String projectId, String versionId, String fileName) {
		entries.put(projectId, new Entry(versionId, fileName));
	}

	public void remove(String projectId) {
		entries.remove(projectId);
	}

	/** Все записи (projectId -> запись), в порядке установки; только для чтения. */
	public Map<String, Entry> entries() {
		return java.util.Collections.unmodifiableMap(entries);
	}

	/** Папка mods, в которой лежит этот реестр. */
	public Path modsDir() {
		return file.getParent();
	}

	public void save() throws IOException {
		Files.createDirectories(file.getParent());

		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			w.write("{\n");

			boolean first = true;

			for (Map.Entry<String, Entry> e : entries.entrySet()) {
				if (!first) w.write(",\n");
				first = false;

				w.write("  \"" + escape(e.getKey()) + "\": {\"versionId\": \"" + escape(e.getValue().versionId)
						+ "\", \"fileName\": \"" + escape(e.getValue().fileName) + "\"}");
			}

			w.write("\n}\n");
		}
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
