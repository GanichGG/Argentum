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
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Минимальный клиент к публичному API Modrinth (api.modrinth.com/v2) — без ключа, без
 * сторонних зависимостей (обычный {@link HttpURLConnection}, как и остальные загрузки в
 * лоадере), ровно тот набор эндпоинтов, что нужен для поиска мода и разрешения его
 * зависимостей. См. https://docs.modrinth.com/api/.
 */
public final class ModrinthClient {
	private static final String API_BASE = "https://api.modrinth.com/v2";
	private static final String USER_AGENT = "ArgentumLoader/" + FabricLoaderImpl.VERSION + " (github.com/GanichGG/ArgentumLoader)";

	private ModrinthClient() { }

	/** Страница результатов поиска: сами проекты и сколько всего их нашлось (для подгрузки по мере прокрутки). */
	public static final class SearchPage {
		public final List<ModrinthProject> hits;
		public final int totalHits;

		SearchPage(List<ModrinthProject> hits, int totalHits) {
			this.hits = hits;
			this.totalHits = totalHits;
		}
	}

	public static List<ModrinthProject> search(String query, String loader, String gameVersion) throws ModrinthApiException {
		return searchPage(query, loader, gameVersion, 0, 30).hits;
	}

	public static SearchPage searchPage(String query, String loader, String gameVersion, int offset, int limit) throws ModrinthApiException {
		return searchPage(query, loader, gameVersion, offset, limit, null, java.util.Collections.emptyList());
	}

	/**
	 * @param sort       relevance / downloads / follows / newest / updated; null — по умолчанию (пустой запрос — по загрузкам)
	 * @param categories категории Modrinth: мод должен быть во всех выбранных
	 */
	public static SearchPage searchPage(String query, String loader, String gameVersion, int offset, int limit, String sort,
			List<String> categories) throws ModrinthApiException {
		StringBuilder facetBuilder = new StringBuilder("[[\"project_type:mod\"],[\"categories:" + loader + "\"],[\"versions:" + gameVersion + "\"]");

		for (String category : categories) {
			facetBuilder.append(",[\"categories:").append(category).append("\"]");
		}

		String facets = facetBuilder.append(']').toString();
		// пустой запрос — витрина самых популярных; иначе обычная релевантность
		String index = sort != null ? sort : query.trim().isEmpty() ? "downloads" : "relevance";
		String url = API_BASE + "/search?query=" + urlEncode(query) + "&index=" + index + "&facets=" + urlEncode(facets)
				+ "&offset=" + offset + "&limit=" + limit;

		try (JsonReader reader = openJsonReader(url)) {
			List<ModrinthProject> ret = new ArrayList<>();
			int total = 0;

			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();

				if (key.equals("total_hits")) {
					total = (int) readLongOrZero(reader);
					continue;
				}

				if (!key.equals("hits")) {
					reader.skipValue();
					continue;
				}

				reader.beginArray();

				while (reader.hasNext()) {
					ret.add(parseProject(reader));
				}

				reader.endArray();
			}

			reader.endObject();

			return new SearchPage(ret, total);
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось выполнить поиск модов на Modrinth по запросу \"" + query + "\"", e);
		}
	}

	/** Полное описание проекта (markdown) — поле {@code body} из {@code GET /v2/project/{id}}; пустая строка, если его нет. */
	public static String getProjectBody(String projectIdOrSlug) throws ModrinthApiException {
		String url = API_BASE + "/project/" + urlEncode(projectIdOrSlug);

		try (JsonReader reader = openJsonReader(url)) {
			String body = "";

			reader.beginObject();

			while (reader.hasNext()) {
				if (reader.nextName().equals("body")) {
					String value = readStringOrNull(reader);
					body = value == null ? "" : value;
				} else {
					reader.skipValue();
				}
			}

			reader.endObject();

			return body;
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось получить с Modrinth описание мода \"" + projectIdOrSlug + "\"", e);
		}
	}

	public static ModrinthProject getProject(String projectIdOrSlug) throws ModrinthApiException {
		String url = API_BASE + "/project/" + urlEncode(projectIdOrSlug);

		try (JsonReader reader = openJsonReader(url)) {
			return parseProject(reader);
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось получить с Modrinth информацию о моде \"" + projectIdOrSlug + "\"", e);
		}
	}

	/** Несколько проектов одним запросом ({@code GET /v2/projects?ids=[...]}). */
	public static List<ModrinthProject> getProjects(List<String> ids) throws ModrinthApiException {
		if (ids.isEmpty()) return new ArrayList<>();

		StringBuilder json = new StringBuilder("[");

		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) json.append(',');
			json.append('"').append(ids.get(i)).append('"');
		}

		String url = API_BASE + "/projects?ids=" + urlEncode(json.append(']').toString());

		try (JsonReader reader = openJsonReader(url)) {
			List<ModrinthProject> ret = new ArrayList<>();

			reader.beginArray();

			while (reader.hasNext()) {
				ret.add(parseProject(reader));
			}

			reader.endArray();

			return ret;
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось получить с Modrinth сведения об установленных модах", e);
		}
	}

	public static List<ModrinthVersion> getProjectVersions(String projectIdOrSlug, String loader, String gameVersion) throws ModrinthApiException {
		String url = API_BASE + "/project/" + urlEncode(projectIdOrSlug) + "/version"
				+ "?loaders=" + urlEncode("[\"" + loader + "\"]")
				+ "&game_versions=" + urlEncode("[\"" + gameVersion + "\"]");

		try (JsonReader reader = openJsonReader(url)) {
			List<ModrinthVersion> ret = new ArrayList<>();

			reader.beginArray();

			while (reader.hasNext()) {
				ret.add(parseVersion(reader));
			}

			reader.endArray();

			return ret;
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось получить с Modrinth список версий мода \"" + projectIdOrSlug + "\""
					+ " под " + gameVersion + "/" + loader, e);
		}
	}

	public static ModrinthVersion getVersion(String versionId) throws ModrinthApiException {
		String url = API_BASE + "/version/" + urlEncode(versionId);

		try (JsonReader reader = openJsonReader(url)) {
			return parseVersion(reader);
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось получить с Modrinth версию мода (id версии: " + versionId + ")", e);
		}
	}

	/** Версии по SHA-1 файлов ({@code POST /v2/version_files}): хеш -> версия. Не найденные на Modrinth хеши в результат не попадают. */
	public static java.util.Map<String, ModrinthVersion> versionsByHashes(List<String> sha1) throws ModrinthApiException {
		if (sha1.isEmpty()) return new java.util.LinkedHashMap<>();

		return readHashMap(openJson(API_BASE + "/version_files", "{\"hashes\":" + quoteList(sha1) + ",\"algorithm\":\"sha1\"}"));
	}

	/** Самая свежая совместимая версия для каждого файла ({@code POST /v2/version_files/update}): хеш -> новейшая версия. */
	public static java.util.Map<String, ModrinthVersion> latestForHashes(List<String> sha1, String loader, String gameVersion) throws ModrinthApiException {
		if (sha1.isEmpty()) return new java.util.LinkedHashMap<>();

		return readHashMap(openJson(API_BASE + "/version_files/update", "{\"hashes\":" + quoteList(sha1) + ",\"algorithm\":\"sha1\",\"loaders\":[\""
				+ loader + "\"],\"game_versions\":[\"" + gameVersion + "\"]}"));
	}

	private static String quoteList(List<String> items) {
		StringBuilder sb = new StringBuilder("[");

		for (int i = 0; i < items.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(items.get(i)).append('"');
		}

		return sb.append(']').toString();
	}

	private static java.util.Map<String, ModrinthVersion> readHashMap(JsonReader reader) throws ModrinthApiException {
		java.util.Map<String, ModrinthVersion> ret = new java.util.LinkedHashMap<>();

		try (JsonReader r = reader) {
			r.beginObject();

			while (r.hasNext()) {
				String hash = r.nextName();
				ret.put(hash, parseVersion(r));
			}

			r.endObject();
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось разобрать ответ Modrinth о версиях по хешам", e);
		}

		return ret;
	}

	private static JsonReader openJsonReader(String url) throws ModrinthApiException {
		return openJson(url, null);
	}

	/**
	 * Открывает JSON-ответ API. Сеть до Modrinth бывает нестабильной (таймауты подключения, 429/5xx), поэтому
	 * временные сбои повторяются три раза с нарастающей паузой; осмысленные ответы (404 и т.п.) — сразу ошибкой.
	 */
	private static JsonReader openJson(String url, String postBody) throws ModrinthApiException {
		final int attempts = 3;
		IOException last = null;

		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				HttpURLConnection connection = ModrinthNet.open(url, 20_000, 30_000);
				connection.setRequestProperty("User-Agent", USER_AGENT);
				connection.setRequestProperty("Accept", "application/json");

				if (postBody != null) {
					connection.setRequestMethod("POST");
					connection.setRequestProperty("Content-Type", "application/json");
					connection.setDoOutput(true);

					try (java.io.OutputStream os = connection.getOutputStream()) {
						os.write(postBody.getBytes(StandardCharsets.UTF_8));
					}
				}

				int status = connection.getResponseCode();

				if (status == 404) {
					throw new ModrinthApiException("Modrinth не нашёл запрошенный мод/версию (404) — возможно, он был удалён или переименован");
				} else if (status == 429 || status >= 500) {
					last = new IOException("HTTP " + status);
				} else if (status != 200) {
					throw new ModrinthApiException("Modrinth ответил с ошибкой: HTTP " + status);
				} else {
					return new JsonReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
				}
			} catch (IOException e) {
				last = e;
			}

			Log.warn(LogCategory.MODRINTH, "Запрос к Modrinth не удался (попытка %d из %d): %s", attempt, attempts, last);

			if (attempt < attempts) {
				try {
					Thread.sleep(800L * attempt);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		throw new ModrinthApiException("Не удалось подключиться к Modrinth — проверь интернет или VPN/прокси. Попробуй ещё раз.", last);
	}

	private static String urlEncode(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new AssertionError(e); // UTF-8 всегда поддерживается
		}
	}

	/**
	 * Разбор проекта — один и для поиска, и для {@code /project/{id}}: у них одни и те же данные под
	 * чуть разными именами ({@code project_id}/{@code id}, {@code follows}/{@code followers},
	 * {@code date_modified}/{@code updated}).
	 */
	private static ModrinthProject parseProject(JsonReader reader) throws IOException {
		String id = null;
		String slug = null;
		String title = null;
		String description = null;
		String author = null;
		long downloads = 0;
		long follows = 0;
		String iconUrl = null;
		String dateModified = null;
		List<String> categories = new ArrayList<>();
		List<String> displayCategories = new ArrayList<>();
		String clientSide = null;
		String serverSide = null;
		int color = 0;

		reader.beginObject();

		while (reader.hasNext()) {
			String key = reader.nextName();

			switch (key) {
			case "project_id": id = readStringOrNull(reader); break;
			case "id": id = readStringOrNull(reader); break;
			case "slug": slug = readStringOrNull(reader); break;
			case "title": title = readStringOrNull(reader); break;
			case "description": description = readStringOrNull(reader); break;
			case "author": author = readStringOrNull(reader); break;
			case "downloads": downloads = readLongOrZero(reader); break;
			case "follows": follows = readLongOrZero(reader); break;
			case "followers": follows = readLongOrZero(reader); break;
			case "icon_url": iconUrl = readStringOrNull(reader); break;
			case "date_modified": dateModified = readStringOrNull(reader); break;
			case "updated": dateModified = readStringOrNull(reader); break;
			case "categories": readStringArray(reader, categories); break;
			case "display_categories": readStringArray(reader, displayCategories); break;
			case "client_side": clientSide = readStringOrNull(reader); break;
			case "server_side": serverSide = readStringOrNull(reader); break;
			case "color": color = (int) readLongOrZero(reader); break;
			default: reader.skipValue();
			}
		}

		reader.endObject();

		return new ModrinthProject(id, slug, title, description, author, downloads, follows, iconUrl, dateModified,
				displayCategories.isEmpty() ? categories : displayCategories, clientSide, serverSide, color);
	}

	private static ModrinthVersion parseVersion(JsonReader reader) throws IOException {
		String id = null;
		String projectId = null;
		String versionNumber = null;
		String name = null;
		List<String> gameVersions = new ArrayList<>();
		List<String> loaders = new ArrayList<>();
		List<ModrinthDependency> dependencies = new ArrayList<>();
		List<ModrinthFile> files = new ArrayList<>();
		String versionType = null;
		String datePublished = null;

		reader.beginObject();

		while (reader.hasNext()) {
			String key = reader.nextName();

			switch (key) {
			case "version_type": versionType = readStringOrNull(reader); break;
			case "date_published": datePublished = readStringOrNull(reader); break;
			case "id": id = readStringOrNull(reader); break;
			case "project_id": projectId = readStringOrNull(reader); break;
			case "version_number": versionNumber = readStringOrNull(reader); break;
			case "name": name = readStringOrNull(reader); break;
			case "game_versions": readStringArray(reader, gameVersions); break;
			case "loaders": readStringArray(reader, loaders); break;
			case "dependencies": readDependencies(reader, dependencies); break;
			case "files": readFiles(reader, files); break;
			default: reader.skipValue();
			}
		}

		reader.endObject();

		return new ModrinthVersion(id, projectId, versionNumber, name, gameVersions, loaders, dependencies, files, versionType, datePublished);
	}

	private static void readDependencies(JsonReader reader, List<ModrinthDependency> out) throws IOException {
		reader.beginArray();

		while (reader.hasNext()) {
			String versionId = null;
			String projectId = null;
			String typeRaw = null;

			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();

				switch (key) {
				case "version_id": versionId = readStringOrNull(reader); break;
				case "project_id": projectId = readStringOrNull(reader); break;
				case "dependency_type": typeRaw = readStringOrNull(reader); break;
				default: reader.skipValue();
				}
			}

			reader.endObject();

			out.add(new ModrinthDependency(versionId, projectId, ModrinthDependency.Type.parse(typeRaw)));
		}

		reader.endArray();
	}

	private static void readFiles(JsonReader reader, List<ModrinthFile> out) throws IOException {
		reader.beginArray();

		while (reader.hasNext()) {
			String url = null;
			String filename = null;
			boolean primary = false;
			String sha1 = null;

			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();

				switch (key) {
				case "url": url = readStringOrNull(reader); break;
				case "filename": filename = readStringOrNull(reader); break;
				case "primary": primary = reader.nextBoolean(); break;
				case "hashes": sha1 = readSha1FromHashes(reader); break;
				default: reader.skipValue();
				}
			}

			reader.endObject();

			out.add(new ModrinthFile(url, filename, primary, sha1));
		}

		reader.endArray();
	}

	private static String readSha1FromHashes(JsonReader reader) throws IOException {
		String sha1 = null;

		reader.beginObject();

		while (reader.hasNext()) {
			String key = reader.nextName();

			if (key.equals("sha1")) {
				sha1 = readStringOrNull(reader);
			} else {
				reader.skipValue();
			}
		}

		reader.endObject();

		return sha1;
	}

	private static void readStringArray(JsonReader reader, List<String> out) throws IOException {
		reader.beginArray();

		while (reader.hasNext()) {
			out.add(reader.nextString());
		}

		reader.endArray();
	}

	private static long readLongOrZero(JsonReader reader) throws IOException {
		if (reader.peek() == JsonToken.NULL) {
			reader.nextNull();
			return 0;
		}

		return reader.nextLong();
	}

	private static String readStringOrNull(JsonReader reader) throws IOException {
		if (reader.peek() == JsonToken.NULL) {
			reader.nextNull();
			return null;
		}

		return reader.nextString();
	}
}
