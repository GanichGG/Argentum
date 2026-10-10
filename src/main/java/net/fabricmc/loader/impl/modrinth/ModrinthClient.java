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

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;

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

	public static List<ModrinthProject> search(String query, String loader, String gameVersion) throws ModrinthApiException {
		String facets = "[[\"project_type:mod\"],[\"categories:" + loader + "\"],[\"versions:" + gameVersion + "\"]]";
		// пустой запрос — витрина самых популярных; иначе обычная релевантность
		String index = query.trim().isEmpty() ? "downloads" : "relevance";
		String url = API_BASE + "/search?query=" + urlEncode(query) + "&index=" + index + "&facets=" + urlEncode(facets) + "&limit=30";

		try (JsonReader reader = openJsonReader(url)) {
			List<ModrinthProject> ret = new ArrayList<>();

			reader.beginObject();

			while (reader.hasNext()) {
				if (!reader.nextName().equals("hits")) {
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

			return ret;
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось выполнить поиск модов на Modrinth по запросу \"" + query + "\"", e);
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

	private static JsonReader openJsonReader(String url) throws ModrinthApiException {
		try {
			HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/json");
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(15_000);

			int status = connection.getResponseCode();

			if (status == 404) {
				throw new ModrinthApiException("Modrinth не нашёл запрошенный мод/версию (404) — возможно, он был удалён или переименован");
			} else if (status != 200) {
				throw new ModrinthApiException("Modrinth ответил с ошибкой: HTTP " + status);
			}

			InputStream is = connection.getInputStream();

			return new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось подключиться к Modrinth — проверь соединение с интернетом", e);
		}
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
			case "project_id":
			case "id": id = readStringOrNull(reader); break;
			case "slug": slug = readStringOrNull(reader); break;
			case "title": title = readStringOrNull(reader); break;
			case "description": description = readStringOrNull(reader); break;
			case "author": author = readStringOrNull(reader); break;
			case "downloads": downloads = readLongOrZero(reader); break;
			case "follows":
			case "followers": follows = readLongOrZero(reader); break;
			case "icon_url": iconUrl = readStringOrNull(reader); break;
			case "date_modified":
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

		reader.beginObject();

		while (reader.hasNext()) {
			String key = reader.nextName();

			switch (key) {
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

		return new ModrinthVersion(id, projectId, versionNumber, name, gameVersions, loaders, dependencies, files);
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
