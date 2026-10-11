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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.loader.impl.hotload.PendingRemovals;
import net.fabricmc.loader.impl.i18n.Lang;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Все моды из папки {@code mods}: включённые ({@code *.jar}) и отключённые ({@code *.jar.disabled}), включая те,
 * что поставлены не через каталог. Для каждого считается SHA-1 и читается {@code fabric.mod.json}; по SHA-1
 * Modrinth опознаёт файл, и он попадает в реестр установленных ({@link InstalledModsIndex}) — тогда его можно обновить,
 * отключить и удалить так же, как поставленный из каталога.
 */
public final class LocalMods {
	public static final String DISABLED_SUFFIX = ".disabled";
	public static final String LOCAL_PREFIX = "local:";

	public static final class Mod {
		/** Имя включённого файла ({@code x.jar}) — под ним мод значится в реестре, даже когда отключён. */
		public final String fileName;
		public final boolean enabled;
		public final String sha1;
		public final String id;
		public final String name;
		public final String version;
		public final String description;
		public final long size;

		Mod(String fileName, boolean enabled, String sha1, String id, String name, String version, String description, long size) {
			this.fileName = fileName;
			this.enabled = enabled;
			this.sha1 = sha1;
			this.id = id;
			this.name = name;
			this.version = version;
			this.description = description;
			this.size = size;
		}
	}

	/** Кэш хешей и метаданных по «путь|размер|время»: пересчитываются только изменившиеся файлы. */
	private static final Map<String, Mod> CACHE = new ConcurrentHashMap<>();

	private LocalMods() { }

	/** Включён ли файл реестра, отключён (в т.ч. отложенно) или его вовсе нет. */
	public enum State { ENABLED, DISABLED, MISSING }

	public static State state(Path modsDir, String fileName) {
		boolean pendingDisable = PendingRemovals.isPendingDisable(fileName);

		if (Files.exists(modsDir.resolve(fileName))) {
			return pendingDisable ? State.DISABLED : State.ENABLED;
		}

		return Files.exists(modsDir.resolve(fileName + DISABLED_SUFFIX)) ? State.DISABLED : State.MISSING;
	}

	/** Отключает мод: переименование в .disabled; true — сделано сразу, false — файл занят игрой, отключится при перезапуске. */
	public static boolean disable(Path modsDir, String fileName) throws IOException {
		Path jar = modsDir.resolve(fileName);

		try {
			Files.move(jar, modsDir.resolve(fileName + DISABLED_SUFFIX), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

			return true;
		} catch (IOException e) {
			PendingRemovals.scheduleDisable(modsDir, fileName);

			return false;
		}
	}

	/** Включает отключённый мод обратно. */
	public static void enable(Path modsDir, String fileName) throws IOException {
		PendingRemovals.cancel(modsDir, fileName);
		Path disabled = modsDir.resolve(fileName + DISABLED_SUFFIX);

		if (Files.exists(disabled)) {
			Files.move(disabled, modsDir.resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Содержимое папки mods (без служебных файлов и без самого Argentum). */
	public static List<Mod> scan(Path modsDir) {
		List<Mod> result = new ArrayList<>();

		if (!Files.isDirectory(modsDir)) {
			return result;
		}

		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String name = p.getFileName().toString();
				boolean enabled = name.endsWith(".jar");

				if ((!enabled && !name.endsWith(".jar" + DISABLED_SUFFIX)) || name.startsWith(".") || !Files.isRegularFile(p)) {
					continue;
				}

				// файл, который уже удалён из списка, но ещё держит игра (Windows): ждёт удаления при запуске — как мод его считать нельзя
				if (enabled && PendingRemovals.isPending(name) && !PendingRemovals.isPendingDisable(name)) {
					continue;
				}

				Mod mod = read(p, enabled ? name : name.substring(0, name.length() - DISABLED_SUFFIX.length()), enabled);

				if (mod != null && !"argentum-client".equals(mod.id)) {
					result.add(mod);
				}
			}
		} catch (IOException e) {
			Log.warn(LogCategory.MODRINTH, "Не удалось просмотреть папку mods: %s", e);
		}

		return result;
	}

	private static Mod read(Path p, String enabledName, boolean enabled) {
		try {
			long size = Files.size(p);
			String key = p + "|" + size + "|" + Files.getLastModifiedTime(p).toMillis();
			Mod cached = CACHE.get(key);

			if (cached != null) {
				return cached;
			}

			String sha1 = sha1(p);
			String[] meta = readMeta(p);
			Mod mod = new Mod(enabledName, enabled, sha1, meta[0], meta[1] != null ? meta[1] : enabledName, meta[2], meta[3], size);
			CACHE.put(key, mod);

			return mod;
		} catch (IOException e) {
			Log.warn(LogCategory.MODRINTH, "Не удалось прочитать %s: %s", p.getFileName(), e);

			return null;
		}
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
			throw new IOException(Lang.tr("SHA-1 недоступен"), e);
		}
	}

	/** {id, name, version, description} из fabric.mod.json; недостающее — null. */
	private static String[] readMeta(Path jar) {
		String[] out = new String[4];

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");

			if (entry == null) {
				return out;
			}

			try (JsonReader r = new JsonReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
				r.setLenient(true);
				r.beginObject();

				while (r.hasNext()) {
					String key = r.nextName();

					if (r.peek() != JsonToken.STRING) {
						r.skipValue();
						continue;
					}

					switch (key) {
					case "id": out[0] = r.nextString(); break;
					case "name": out[1] = r.nextString(); break;
					case "version": out[2] = r.nextString(); break;
					case "description": out[3] = r.nextString(); break;
					default: r.skipValue();
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// нечитаемые метаданные — покажем по имени файла
		}

		return out;
	}

	/**
	 * Опознаёт моды, которых нет в реестре, по SHA-1 на Modrinth и дописывает их; не найденные записываются как
	 * локальные. Возвращает число добавленных записей. Нужна сеть только для неопознанных файлов.
	 */
	public static int identify(Path modsDir, InstalledModsIndex index) throws ModrinthApiException {
		// записи о файлах, которых больше нет (удалили руками), из реестра убираем
		boolean pruned = false;

		for (Map.Entry<String, InstalledModsIndex.Entry> e : new ArrayList<>(index.entries().entrySet())) {
			if (state(modsDir, e.getValue().fileName) == State.MISSING && !PendingRemovals.isPending(e.getValue().fileName)) {
				index.remove(e.getKey());
				pruned = true;
			}
		}

		Map<String, String> known = new ConcurrentHashMap<>(); // имя файла -> projectId

		for (Map.Entry<String, InstalledModsIndex.Entry> e : index.entries().entrySet()) {
			known.put(e.getValue().fileName, e.getKey());
		}

		List<Mod> unknown = new ArrayList<>();

		for (Mod mod : scan(modsDir)) {
			if (!known.containsKey(mod.fileName)) {
				unknown.add(mod);
			}
		}

		if (unknown.isEmpty()) {
			if (pruned) {
				try {
					index.save();
				} catch (IOException e) {
					Log.warn(LogCategory.MODRINTH, "Не удалось сохранить реестр модов: %s", e);
				}
			}

			return 0;
		}

		List<String> hashes = new ArrayList<>();

		for (Mod m : unknown) {
			hashes.add(m.sha1);
		}

		Map<String, ModrinthVersion> found = ModrinthClient.versionsByHashes(hashes);
		int added = 0;

		for (Mod m : unknown) {
			ModrinthVersion v = found.get(m.sha1);

			if (v != null && v.projectId != null && !index.contains(v.projectId)) {
				index.put(v.projectId, v.id, m.fileName);
			} else {
				index.put(LOCAL_PREFIX + m.fileName, "local", m.fileName);
			}

			added++;
		}

		try {
			index.save();
		} catch (IOException e) {
			Log.warn(LogCategory.MODRINTH, "Не удалось сохранить реестр модов: %s", e);
		}

		return added;
	}

	/** Мод по имени файла из реестра (включённый или отключённый); null — файла нет. */
	public static Mod find(Path modsDir, String fileName) {
		for (Mod m : scan(modsDir)) {
			if (m.fileName.equals(fileName)) {
				return m;
			}
		}

		return null;
	}
}
