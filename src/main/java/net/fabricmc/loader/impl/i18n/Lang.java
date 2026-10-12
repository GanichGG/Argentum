/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.i18n;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;

/**
 * Локализация «текст как ключ»: в коде остаётся оригинал на русском, а перевод ищется по нему в таблицах
 * {@code argentum/lang/<язык>.json} (объект «оригинал → перевод»). Таблицы собираются со всего classpath, поэтому свои
 * строки добавляют и загрузчик, и клиентский мод. Нет перевода — показывается оригинал.
 *
 * <p>Язык берётся из {@code -Dargentum.lang=}, иначе из {@code lang:} в {@code options.txt} игры, иначе из языка системы.
 * Русский — язык оригиналов, таблица ему не нужна; любой неизвестный язык получает английский.
 */
public final class Lang {
	private static final String PATH = "argentum/lang/";

	private static volatile Map<String, String> table = Collections.emptyMap();
	private static volatile String loadedFor;

	private Lang() { }

	/** Перевод строки-оригинала; с аргументами — как {@link String#format}. */
	public static String tr(String original) {
		ensureLoaded();
		String translated = table.get(original);

		return translated != null ? translated : original;
	}

	public static String tr(String original, Object... args) {
		return String.format(tr(original), args);
	}

	/** Читает язык и таблицы заново (игрок мог сменить язык в настройках игры, а моды — добавить свои таблицы). */
	public static void refresh() {
		load(detect());
	}

	/**
	 * Таблицы лежат в jar'ах модов, которые становятся видны не сразу: что-то могло запросить перевод до того, как загрузился
	 * клиентский мод. Поэтому при каждом добавлении jar в classpath загрузка помечается устаревшей и выполняется заново при
	 * следующем запросе.
	 */
	public static void invalidate() {
		loadedFor = null;
	}

	/** Код текущего языка в нижнем регистре, например {@code ru_ru}. */
	public static String language() {
		ensureLoaded();

		return loadedFor;
	}

	private static void ensureLoaded() {
		if (loadedFor == null) {
			load(detect());
		}
	}

	private static String detect() {
		String forced = System.getProperty("argentum.lang");

		if (forced != null && !forced.isEmpty()) {
			return forced.toLowerCase(Locale.ROOT);
		}

		try {
			Path gameDir = FabricLoaderImpl.INSTANCE != null ? FabricLoaderImpl.INSTANCE.getGameDir() : Paths.get(".");
			Path options = gameDir.resolve("options.txt");

			if (Files.isRegularFile(options)) {
				List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);

				for (String line : lines) {
					if (line.startsWith("lang:") && line.length() > 5) {
						return line.substring(5).trim().toLowerCase(Locale.ROOT);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// нет настроек — берём язык системы
		}

		return Locale.getDefault().toString().toLowerCase(Locale.ROOT);
	}

	private static synchronized void load(String code) {
		Map<String, String> merged = new HashMap<>();

		if (!code.startsWith("ru")) { // русский — язык оригиналов
			Set<String> names = new LinkedHashSet<>();
			names.add("en"); // подложка: что не переведено на нужный язык, показываем по-английски
			String base = code.length() > 2 && code.charAt(2) == '_' ? code.substring(0, 2) : code;
			names.add(base);
			names.add(code);

			for (String name : names) {
				readAll(PATH + name + ".json", merged);
			}
		}

		table = merged;
		loadedFor = code;
	}

	private static void readAll(String resource, Map<String, String> into) {
		Set<URL> urls = new LinkedHashSet<>();

		for (ClassLoader cl : new ClassLoader[] {Thread.currentThread().getContextClassLoader(), Lang.class.getClassLoader(), ClassLoader.getSystemClassLoader()}) {
			if (cl == null) {
				continue;
			}

			try {
				Enumeration<URL> found = cl.getResources(resource);

				while (found.hasMoreElements()) {
					urls.add(found.nextElement());
				}
			} catch (IOException e) {
				// этот загрузчик ресурсов недоступен
			}
		}

		for (URL url : urls) {
			try (Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8); JsonReader json = new JsonReader(reader)) {
				json.beginObject();

				while (json.hasNext()) {
					String key = json.nextName();

					if (json.peek() == JsonToken.STRING) {
						String value = json.nextString();

						if (!value.isEmpty()) {
							into.put(key, value);
						}
					} else {
						json.skipValue();
					}
				}

				json.endObject();
			} catch (IOException | RuntimeException e) {
				// повреждённая таблица не должна ломать игру — просто не получим этот перевод
			}
		}
	}
}
