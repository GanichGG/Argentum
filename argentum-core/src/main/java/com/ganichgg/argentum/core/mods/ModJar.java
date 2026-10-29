/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.mods;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.ganichgg.argentum.core.json.JsonReader;
import com.ganichgg.argentum.core.json.JsonToken;

/**
 * Что за мод лежит в jar: вид (Fabric, Forge, NeoForge, Quilt) по его файлу описания, плюс id, имя и версия. Основа
 * гибридного ядра: по виду решается, через какой слой мод пойдёт. Только чтение jar, без загрузки классов.
 */
public final class ModJar {
	/** Вид мода. */
	public enum Kind {
		/** {@code META-INF/neoforge.mods.toml}. */
		NEOFORGE,
		/** {@code META-INF/mods.toml} без описания NeoForge — мод Forge. */
		FORGE,
		/** {@code fabric.mod.json}. */
		FABRIC,
		/** {@code quilt.mod.json} (без описания Fabric). */
		QUILT,
		/** Библиотека или неизвестный jar. */
		UNKNOWN
	}

	/** Один мод из jar (в одном jar Forge/NeoForge бывает несколько модов). */
	public static final class Mod {
		public final String id;
		public final String name;
		public final String version;

		Mod(String id, String name, String version) {
			this.id = id;
			this.name = name;
			this.version = version;
		}

		@Override
		public String toString() {
			return id + " " + version;
		}
	}

	public final Path file;
	public final Kind kind;
	public final List<Mod> mods;
	/** Есть описания сразу нескольких видов (мод сразу для Fabric и NeoForge в одном jar). */
	public final List<Kind> alsoHas;

	private ModJar(Path file, Kind kind, List<Mod> mods, List<Kind> alsoHas) {
		this.file = file;
		this.kind = kind;
		this.mods = mods;
		this.alsoHas = alsoHas;
	}

	private static final Pattern TOML_KEY = Pattern.compile("^\\s*(modId|displayName|version)\\s*=\\s*\"([^\"]*)\"");

	/** Разбирает jar; нечитаемый — {@link Kind#UNKNOWN}. */
	public static ModJar read(Path file) {
		try (ZipFile zip = new ZipFile(file.toFile())) {
			ZipEntry neo = zip.getEntry("META-INF/neoforge.mods.toml");
			ZipEntry forge = zip.getEntry("META-INF/mods.toml");
			ZipEntry fabric = zip.getEntry("fabric.mod.json");
			ZipEntry quilt = zip.getEntry("quilt.mod.json");
			List<Kind> present = new ArrayList<>();

			if (neo != null) {
				present.add(Kind.NEOFORGE);
			}

			if (forge != null) {
				present.add(Kind.FORGE);
			}

			if (fabric != null) {
				present.add(Kind.FABRIC);
			}

			if (quilt != null) {
				present.add(Kind.QUILT);
			}

			if (present.isEmpty()) {
				return new ModJar(file, Kind.UNKNOWN, Collections.emptyList(), Collections.emptyList());
			}

			Kind kind = present.get(0);
			List<Mod> mods;

			switch (kind) {
			case NEOFORGE:
				mods = readToml(zip, neo, file);
				break;
			case FORGE:
				mods = readToml(zip, forge, file);
				break;
			case FABRIC:
				mods = readFabric(zip, fabric);
				break;
			default:
				mods = Collections.emptyList();
			}

			return new ModJar(file, kind, mods, present.subList(1, present.size()));
		} catch (IOException | RuntimeException e) {
			return new ModJar(file, Kind.UNKNOWN, Collections.emptyList(), Collections.emptyList());
		}
	}

	/**
	 * Моды из {@code mods.toml}: блоки {@code [[mods]]} с modId, displayName и version. Полноценный разбор TOML не нужен —
	 * только эти ключи. {@code ${file.jarVersion}} заменяется версией из манифеста.
	 */
	static List<Mod> readToml(ZipFile zip, ZipEntry entry, Path file) throws IOException {
		String text = read(zip.getInputStream(entry));
		String jarVersion = manifestVersion(zip);
		List<Mod> mods = new ArrayList<>();
		String id = null;
		String name = null;
		String version = null;
		boolean inMods = false;

		for (String line : text.split("\r?\n")) {
			String t = line.trim();

			if (t.startsWith("[")) {
				if (inMods && id != null) {
					mods.add(new Mod(id, name != null ? name : id, fill(version, jarVersion)));
				}

				inMods = t.equals("[[mods]]");
				id = name = version = null;
				continue;
			}

			if (!inMods) {
				continue;
			}

			Matcher m = TOML_KEY.matcher(line);

			if (m.find()) {
				switch (m.group(1)) {
				case "modId":
					id = m.group(2);
					break;
				case "displayName":
					name = m.group(2);
					break;
				default:
					version = m.group(2);
				}
			}
		}

		if (inMods && id != null) {
			mods.add(new Mod(id, name != null ? name : id, fill(version, jarVersion)));
		}

		return mods;
	}

	private static String fill(String version, String jarVersion) {
		if (version == null) {
			return "?";
		}

		return version.contains("${file.jarVersion}") ? version.replace("${file.jarVersion}", jarVersion == null ? "?" : jarVersion) : version;
	}

	private static String manifestVersion(ZipFile zip) throws IOException {
		ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");

		if (mf == null) {
			return null;
		}

		for (String line : read(zip.getInputStream(mf)).split("\r?\n")) {
			if (line.startsWith("Implementation-Version:")) {
				return line.substring("Implementation-Version:".length()).trim();
			}
		}

		return null;
	}

	private static List<Mod> readFabric(ZipFile zip, ZipEntry entry) throws IOException {
		String id = null;
		String name = null;
		String version = null;

		try (Reader r = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8); JsonReader json = new JsonReader(r)) {
			json.setLenient(true);
			json.beginObject();

			while (json.hasNext()) {
				String key = json.nextName();

				if (json.peek() != JsonToken.STRING) {
					json.skipValue();
					continue;
				}

				switch (key) {
				case "id":
					id = json.nextString();
					break;
				case "name":
					name = json.nextString();
					break;
				case "version":
					version = json.nextString();
					break;
				default:
					json.skipValue();
				}
			}
		}

		return id == null ? Collections.emptyList() : Collections.singletonList(new Mod(id, name != null ? name : id, version != null ? version : "?"));
	}

	private static String read(InputStream in) throws IOException {
		try (InputStream is = in) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;

			while ((n = is.read(buf)) > 0) {
				out.write(buf, 0, n);
			}

			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
