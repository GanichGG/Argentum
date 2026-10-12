/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Небольшой дисковый кэш ответов Modrinth, чтобы каталог открывался мгновенно и работал при плохом интернете:
 * карточки проектов, первая страница популярного, полные описания и байты иконок. Данные кладутся рядом с игрой
 * (в переданную папку), а не в память — переживают перезапуск.
 *
 * <p>Умеренность: проекты режутся до {@value #MAX_PROJECTS} самых свежих записей, иконки не кэшируются больше
 * {@value #MAX_ICON_BYTES} байт, описания — до {@value #MAX_BODY_CHARS} символов.
 */
public final class ModrinthCache {
	private static final int MAX_PROJECTS = 400;
	private static final int MAX_ICON_BYTES = 512 * 1024;
	private static final int MAX_BODY_CHARS = 20_000;

	private final Path dir;
	private final Map<String, Entry> projects = new LinkedHashMap<>();
	private final Map<String, ListEntry> lists = new LinkedHashMap<>();
	private boolean dirty;

	private static final class Entry {
		final ModrinthProject project;
		final long cachedAt;

		Entry(ModrinthProject project, long cachedAt) {
			this.project = project;
			this.cachedAt = cachedAt;
		}
	}

	private static final class ListEntry {
		final List<String> ids;
		final int total;
		final long cachedAt;

		ListEntry(List<String> ids, int total, long cachedAt) {
			this.ids = ids;
			this.total = total;
			this.cachedAt = cachedAt;
		}
	}

	private ModrinthCache(Path dir) {
		this.dir = dir;
	}

	public static ModrinthCache open(Path dir) {
		ModrinthCache cache = new ModrinthCache(dir);
		cache.loadProjects();
		cache.loadLists();

		return cache;
	}

	// ---------------------------------------------------------------- проекты и списки

	public synchronized ModrinthProject project(String id) {
		Entry e = projects.get(id);

		return e == null ? null : e.project;
	}

	public synchronized void putProjects(Collection<ModrinthProject> toStore) {
		long now = System.currentTimeMillis();

		for (ModrinthProject p : toStore) {
			if (p.id == null) continue;

			// у проекта, полученного по id, нет автора — не затираем им уже известное значение из поиска
			ModrinthProject old = project(p.id);
			ModrinthProject merged = p.author == null && old != null && old.author != null ? withAuthor(p, old.author) : p;
			projects.remove(p.id); // чтобы запись переехала в конец (свежие — последние)
			projects.put(p.id, new Entry(merged, now));
		}

		dirty = true;
	}

	private static ModrinthProject withAuthor(ModrinthProject p, String author) {
		return new ModrinthProject(p.id, p.slug, p.title, p.description, author, p.downloads, p.follows, p.iconUrl,
				p.dateModified, p.categories, p.clientSide, p.serverSide, p.color);
	}

	public synchronized void putList(String key, List<ModrinthProject> list, int total) {
		putProjects(list);
		List<String> ids = new ArrayList<>(list.size());

		for (ModrinthProject p : list) {
			ids.add(p.id);
		}

		lists.put(key, new ListEntry(ids, total, System.currentTimeMillis()));
		dirty = true;
	}

	/** Сохранённый список проектов или null; {@code maxAgeMs} < 0 — без ограничения по возрасту (оффлайн). */
	public synchronized List<ModrinthProject> list(String key, long maxAgeMs) {
		ListEntry e = lists.get(key);

		if (e == null || (maxAgeMs >= 0 && System.currentTimeMillis() - e.cachedAt > maxAgeMs)) {
			return null;
		}

		List<ModrinthProject> ret = new ArrayList<>(e.ids.size());

		for (String id : e.ids) {
			ModrinthProject p = project(id);

			if (p != null) ret.add(p);
		}

		return ret;
	}

	public synchronized int listTotal(String key) {
		ListEntry e = lists.get(key);

		return e == null ? 0 : e.total;
	}

	// ---------------------------------------------------------------- описания и иконки

	/** Полное описание проекта, если оно есть в кэше и не старше {@code maxAgeMs}. */
	public String body(String id, long maxAgeMs) {
		Path file = dir.resolve("bodies").resolve(safeName(id) + ".txt");

		try {
			if (!Files.isRegularFile(file) || System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > maxAgeMs) {
				return null;
			}

			return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

	public void putBody(String id, String body) {
		String trimmed = body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;

		writeFile(dir.resolve("bodies").resolve(safeName(id) + ".txt"), trimmed.getBytes(StandardCharsets.UTF_8));
	}

	public byte[] icon(String id, long maxAgeMs) {
		Path file = dir.resolve("icons").resolve(safeName(id) + ".img");

		try {
			if (!Files.isRegularFile(file) || System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > maxAgeMs) {
				return null;
			}

			return Files.readAllBytes(file);
		} catch (IOException e) {
			return null;
		}
	}

	public void putIcon(String id, byte[] bytes) {
		if (bytes.length <= MAX_ICON_BYTES) {
			writeFile(dir.resolve("icons").resolve(safeName(id) + ".img"), bytes);
		}
	}

	private static String safeName(String id) {
		return id.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	private void writeFile(Path file, byte[] bytes) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, bytes);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Log.warn(LogCategory.MODRINTH, "Не удалось записать кэш %s: %s", file.getFileName(), e);
		}
	}

	// ---------------------------------------------------------------- сохранение и чтение JSON

	public synchronized void save() {
		if (!dirty) return;

		// держим кэш умеренным: оставляем только самые свежие записи
		while (projects.size() > MAX_PROJECTS) {
			String oldest = projects.keySet().iterator().next();
			projects.remove(oldest);
		}

		StringBuilder sb = new StringBuilder("{\n");
		boolean first = true;

		for (Map.Entry<String, Entry> e : projects.entrySet()) {
			if (!first) sb.append(",\n");
			first = false;
			appendProject(sb, e.getKey(), e.getValue());
		}

		sb.append("\n}\n");
		writeFile(dir.resolve("projects.json"), sb.toString().getBytes(StandardCharsets.UTF_8));

		sb = new StringBuilder("{\n");
		first = true;

		for (Map.Entry<String, ListEntry> e : lists.entrySet()) {
			if (!first) sb.append(",\n");
			first = false;
			sb.append("  ").append(str(e.getKey())).append(": {\"cachedAt\": ").append(e.getValue().cachedAt)
					.append(", \"total\": ").append(e.getValue().total).append(", \"ids\": [");

			for (int i = 0; i < e.getValue().ids.size(); i++) {
				if (i > 0) sb.append(", ");
				sb.append(str(e.getValue().ids.get(i)));
			}

			sb.append("]}");
		}

		sb.append("\n}\n");
		writeFile(dir.resolve("lists.json"), sb.toString().getBytes(StandardCharsets.UTF_8));

		dirty = false;
	}

	private static void appendProject(StringBuilder sb, String id, Entry e) {
		ModrinthProject p = e.project;
		sb.append("  ").append(str(id)).append(": {")
				.append("\"cachedAt\": ").append(e.cachedAt)
				.append(", \"slug\": ").append(str(p.slug))
				.append(", \"title\": ").append(str(p.title))
				.append(", \"description\": ").append(str(p.description))
				.append(", \"author\": ").append(str(p.author))
				.append(", \"downloads\": ").append(p.downloads)
				.append(", \"follows\": ").append(p.follows)
				.append(", \"iconUrl\": ").append(str(p.iconUrl))
				.append(", \"dateModified\": ").append(str(p.dateModified))
				.append(", \"clientSide\": ").append(str(p.clientSide))
				.append(", \"serverSide\": ").append(str(p.serverSide))
				.append(", \"color\": ").append(p.color)
				.append(", \"categories\": [");

		for (int i = 0; i < p.categories.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(str(p.categories.get(i)));
		}

		sb.append("]}");
	}

	private static String str(String s) {
		if (s == null) return "null";

		StringBuilder sb = new StringBuilder("\"");

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			switch (c) {
			case '"': sb.append("\\\""); break;
			case '\\': sb.append("\\\\"); break;
			case '\n': sb.append("\\n"); break;
			case '\r': sb.append("\\r"); break;
			case '\t': sb.append("\\t"); break;
			default:
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
		}

		return sb.append('"').toString();
	}

	private void loadProjects() {
		Path file = dir.resolve("projects.json");

		if (!Files.isRegularFile(file)) return;

		try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				String id = reader.nextName();
				long cachedAt = 0;
				String slug = null;
				String title = null;
				String description = null;
				String author = null;
				long downloads = 0;
				long follows = 0;
				String iconUrl = null;
				String dateModified = null;
				String clientSide = null;
				String serverSide = null;
				int color = 0;
				List<String> categories = new ArrayList<>();

				reader.beginObject();

				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "cachedAt": cachedAt = reader.nextLong(); break;
					case "slug": slug = nullableString(reader); break;
					case "title": title = nullableString(reader); break;
					case "description": description = nullableString(reader); break;
					case "author": author = nullableString(reader); break;
					case "downloads": downloads = reader.nextLong(); break;
					case "follows": follows = reader.nextLong(); break;
					case "iconUrl": iconUrl = nullableString(reader); break;
					case "dateModified": dateModified = nullableString(reader); break;
					case "clientSide": clientSide = nullableString(reader); break;
					case "serverSide": serverSide = nullableString(reader); break;
					case "color": color = (int) reader.nextLong(); break;
					case "categories":
						reader.beginArray();

						while (reader.hasNext()) categories.add(reader.nextString());

						reader.endArray();
						break;
					default: reader.skipValue();
					}
				}

				reader.endObject();
				projects.put(id, new Entry(new ModrinthProject(id, slug, title, description, author, downloads, follows,
						iconUrl, dateModified, categories, clientSide, serverSide, color), cachedAt));
			}

			reader.endObject();
		} catch (IOException | RuntimeException e) {
			Log.warn(LogCategory.MODRINTH, "Кэш проектов повреждён, начинаю заново: %s", e);
			projects.clear();
		}
	}

	private void loadLists() {
		Path file = dir.resolve("lists.json");

		if (!Files.isRegularFile(file)) return;

		try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();
				long cachedAt = 0;
				int total = 0;
				List<String> ids = new ArrayList<>();

				reader.beginObject();

				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "cachedAt": cachedAt = reader.nextLong(); break;
					case "total": total = (int) reader.nextLong(); break;
					case "ids":
						reader.beginArray();

						while (reader.hasNext()) ids.add(reader.nextString());

						reader.endArray();
						break;
					default: reader.skipValue();
					}
				}

				reader.endObject();
				lists.put(key, new ListEntry(Collections.unmodifiableList(ids), total, cachedAt));
			}

			reader.endObject();
		} catch (IOException | RuntimeException e) {
			Log.warn(LogCategory.MODRINTH, "Кэш списков повреждён, начинаю заново: %s", e);
			lists.clear();
		}
	}

	private static String nullableString(JsonReader reader) throws IOException {
		if (reader.peek() == JsonToken.NULL) {
			reader.nextNull();
			return null;
		}

		return reader.nextString();
	}
}
