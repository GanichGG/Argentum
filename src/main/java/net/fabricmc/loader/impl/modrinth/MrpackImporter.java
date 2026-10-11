/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Импорт сборки Modrinth ({@code .mrpack}) в текущую папку игры: скачивает файлы из {@code modrinth.index.json}
 * (с проверкой SHA-1 и разрешёнными хостами), распаковывает {@code overrides}/{@code client-overrides} и ничего
 * не перезаписывает у пользователя без нужды. Сборки под другую версию игры или другой загрузчик не принимает.
 */
public final class MrpackImporter {
	private static final List<String> ALLOWED_HOSTS = Arrays.asList("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");
	private static final List<String> FILE_DIRS = Arrays.asList("mods", "resourcepacks", "shaderpacks");
	private static final List<String> OVERRIDE_DIRS = Arrays.asList("mods", "config", "resourcepacks", "shaderpacks");

	public interface Progress {
		void update(int done, int total, String what);
	}

	/** Разобранный индекс сборки. */
	public static final class Pack {
		public String name = "сборка";
		public String minecraft;
		public String fabricLoader;
		public String otherLoader;
		final List<Item> items = new ArrayList<>();

		public int fileCount() {
			return this.items.size();
		}
	}

	static final class Item {
		String path;
		String sha1;
		boolean clientSupported = true;
		final List<String> urls = new ArrayList<>();
	}

	public static final class Result {
		public final String name;
		public final List<Path> newMods;
		public final int downloaded;
		public final int skipped;
		public final int overrides;

		Result(String name, List<Path> newMods, int downloaded, int skipped, int overrides) {
			this.name = name;
			this.newMods = newMods;
			this.downloaded = downloaded;
			this.skipped = skipped;
			this.overrides = overrides;
		}
	}

	private MrpackImporter() { }

	public static Pack read(Path mrpack) throws IOException {
		try (ZipFile zip = new ZipFile(mrpack.toFile())) {
			ZipEntry entry = zip.getEntry("modrinth.index.json");

			if (entry == null) {
				throw new IOException("в архиве нет modrinth.index.json — это не сборка Modrinth");
			}

			return parse(zip.getInputStream(entry));
		}
	}

	static Pack parse(InputStream in) throws IOException {
		Pack pack = new Pack();

		try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			r.beginObject();

			while (r.hasNext()) {
				switch (r.nextName()) {
				case "name":
					pack.name = r.nextString();
					break;
				case "dependencies":
					r.beginObject();

					while (r.hasNext()) {
						String key = r.nextName();
						String value = r.nextString();

						if (key.equals("minecraft")) {
							pack.minecraft = value;
						} else if (key.equals("fabric-loader")) {
							pack.fabricLoader = value;
						} else {
							pack.otherLoader = key;
						}
					}

					r.endObject();
					break;
				case "files":
					r.beginArray();

					while (r.hasNext()) {
						pack.items.add(readItem(r));
					}

					r.endArray();
					break;
				default:
					r.skipValue();
				}
			}

			r.endObject();
		}

		return pack;
	}

	private static Item readItem(JsonReader r) throws IOException {
		Item item = new Item();
		r.beginObject();

		while (r.hasNext()) {
			switch (r.nextName()) {
			case "path":
				item.path = r.nextString();
				break;
			case "hashes":
				r.beginObject();

				while (r.hasNext()) {
					String algo = r.nextName();
					String value = r.nextString();

					if (algo.equals("sha1")) {
						item.sha1 = value.toLowerCase(Locale.ROOT);
					}
				}

				r.endObject();
				break;
			case "env":
				r.beginObject();

				while (r.hasNext()) {
					String side = r.nextName();
					String value = r.peek() == JsonToken.STRING ? r.nextString() : "";

					if (side.equals("client") && value.equals("unsupported")) {
						item.clientSupported = false;
					}
				}

				r.endObject();
				break;
			case "downloads":
				r.beginArray();

				while (r.hasNext()) {
					item.urls.add(r.nextString());
				}

				r.endArray();
				break;
			default:
				r.skipValue();
			}
		}

		r.endObject();

		return item;
	}

	/** Проверка совместимости; бросает понятную ошибку, если сборку ставить нельзя. */
	public static void check(Pack pack, String gameVersion) throws IOException {
		if (pack.minecraft != null && !pack.minecraft.equals(gameVersion)) {
			throw new IOException("Сборка «" + pack.name + "» сделана для Minecraft " + pack.minecraft + ", а у тебя " + gameVersion);
		}

		if (pack.fabricLoader == null) {
			throw new IOException("Сборка «" + pack.name + "» не для Fabric" + (pack.otherLoader != null ? " (" + pack.otherLoader + ")" : ""));
		}
	}

	public static Result importPack(Path mrpack, Path gameDir, String gameVersion, Progress progress) throws IOException {
		Pack pack = read(mrpack);
		check(pack, gameVersion);

		List<Item> todo = new ArrayList<>();

		for (Item item : pack.items) {
			if (item.clientSupported) {
				todo.add(item);
			}
		}

		List<Path> newMods = new ArrayList<>();
		int downloaded = 0;
		int skipped = 0;
		int step = 0;
		int total = todo.size() + 1;

		for (Item item : todo) {
			if (progress != null) {
				progress.update(step++, total, item.path);
			}

			Path target = safeTarget(gameDir, item.path, FILE_DIRS);

			if (Files.exists(target) && item.sha1 != null && item.sha1.equals(sha1(target))) {
				skipped++;
				continue;
			}

			download(item, target);
			downloaded++;

			if (item.path.startsWith("mods/") && item.path.endsWith(".jar")) {
				newMods.add(target);
			}
		}

		if (progress != null) {
			progress.update(step, total, "файлы из сборки");
		}

		int overrides = extractOverrides(mrpack, gameDir, newMods);

		return new Result(pack.name, newMods, downloaded, skipped, overrides);
	}

	private static Path safeTarget(Path gameDir, String relative, List<String> allowedDirs) throws IOException {
		Path root = gameDir.toAbsolutePath().normalize();
		Path target = root.resolve(relative).normalize();

		if (!target.startsWith(root)) {
			throw new IOException("сборка пытается записать файл вне папки игры: " + relative);
		}

		Path rel = root.relativize(target);

		if (rel.getNameCount() < 2 || !allowedDirs.contains(rel.getName(0).toString())) {
			throw new IOException("сборка пытается записать файл вне разрешённых папок: " + relative);
		}

		return target;
	}

	private static void download(Item item, Path target) throws IOException {
		IOException last = null;

		for (String url : item.urls) {
			String host = java.net.URI.create(url).getHost();

			if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
				last = new IOException("хост " + host + " не входит в разрешённые для сборок");
				continue;
			}

			for (int attempt = 1; attempt <= 3; attempt++) {
				Path tmp = target.resolveSibling(target.getFileName() + ".tmp");

				try {
					Files.createDirectories(target.getParent());
					HttpURLConnection c = ModrinthNet.open(url, 30_000, 60_000);

					if (c.getResponseCode() != 200) {
						throw new IOException("HTTP " + c.getResponseCode());
					}

					try (InputStream is = c.getInputStream(); OutputStream os = Files.newOutputStream(tmp)) {
						byte[] buf = new byte[65536];
						int n;

						while ((n = is.read(buf)) >= 0) {
							os.write(buf, 0, n);
						}
					}

					if (item.sha1 != null && !item.sha1.equals(sha1(tmp))) {
						throw new IOException("контрольная сумма " + item.path + " не совпала");
					}

					Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

					return;
				} catch (IOException e) {
					last = e;
					Files.deleteIfExists(tmp);
					Log.warn(LogCategory.MODRINTH, "Попытка %d/3 скачать %s не удалась: %s", attempt, item.path, e);
				}
			}
		}

		throw new IOException("не удалось скачать " + item.path + (last != null ? ": " + last.getMessage() : ""), last);
	}

	private static int extractOverrides(Path mrpack, Path gameDir, List<Path> newMods) throws IOException {
		int count = 0;

		try (ZipFile zip = new ZipFile(mrpack.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				String name = e.getName();
				String rel;

				if (name.startsWith("overrides/")) {
					rel = name.substring("overrides/".length());
				} else if (name.startsWith("client-overrides/")) {
					rel = name.substring("client-overrides/".length());
				} else {
					continue;
				}

				if (e.isDirectory() || rel.isEmpty()) {
					continue;
				}

				Path target;

				try {
					target = safeTarget(gameDir, rel, OVERRIDE_DIRS);
				} catch (IOException skip) {
					continue; // всё, что вне разрешённых папок (options.txt, servers.dat...), не трогаем
				}

				if (Files.exists(target)) {
					continue; // настройки пользователя не перезаписываем
				}

				Files.createDirectories(target.getParent());

				try (InputStream is = zip.getInputStream(e)) {
					Files.copy(is, target);
				}

				count++;

				if (rel.startsWith("mods/") && rel.endsWith(".jar")) {
					newMods.add(target);
				}
			}
		}

		return count;
	}

	private static String sha1(Path p) throws IOException {
		try (InputStream is = Files.newInputStream(p)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[65536];
			int n;

			while ((n = is.read(buf)) >= 0) {
				md.update(buf, 0, n);
			}

			StringBuilder sb = new StringBuilder();

			for (byte b : md.digest()) {
				sb.append(String.format("%02x", b));
			}

			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}
}
