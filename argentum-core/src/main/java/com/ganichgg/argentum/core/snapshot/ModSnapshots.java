/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.ganichgg.argentum.core.files.PendingRemovals;
import com.ganichgg.argentum.core.log.Log;
import com.ganichgg.argentum.core.log.LogCategory;

/**
 * Снимки рабочего набора модов: при каждом удачном запуске (игра дошла до главного меню) запоминается, какие jar лежали
 * в {@code mods} (имя, размер, SHA-1), а сами файлы попадают в хранилище {@code .argentum/mod-store/<sha1>.jar} —
 * жёсткой ссылкой, без лишнего места на диске (если ссылка невозможна — копией). Тогда «вернуть, как было» работает,
 * даже если обновление уже удалило старый файл.
 *
 * <p>Хранятся три последних разных набора; откат выбирает самый свежий, который отличается от текущего. Откат
 * выполняется при запуске до поиска модов: лишние jar уходят (занятые прошлой копией игры — откладываются), недостающие
 * возвращаются из хранилища, восстанавливается и реестр модов каталога.
 */
public final class ModSnapshots {
	/** Просьба откатиться при следующем запуске ({@code .argentum/rollback-request}). */
	public static final String REQUEST_FILE = "rollback-request";
	static final String RESULT_FILE = "rollback-result.txt";
	private static final String SNAP_DIR = "snapshots";
	private static final String STORE_DIR = "mod-store";
	private static final String INDEX_FILE = ".argentum-modrinth.json";
	private static final int KEEP = 3;

	/** Один jar набора. */
	public static final class Entry {
		public final String file;
		public final String sha1;
		public final long size;
		final long mtime;

		Entry(String file, String sha1, long size, long mtime) {
			this.file = file;
			this.sha1 = sha1;
			this.size = size;
			this.mtime = mtime;
		}
	}

	/** Набор модов на момент удачного запуска. */
	public static final class Snapshot {
		public final String id;
		public final long time;
		public final List<Entry> entries;

		Snapshot(String id, long time, List<Entry> entries) {
			this.id = id;
			this.time = time;
			this.entries = entries;
		}

		Set<String> hashes() {
			Set<String> out = new HashSet<>();

			for (Entry e : entries) {
				out.add(e.sha1);
			}

			return out;
		}

		public String date() {
			return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time));
		}
	}

	/** Разница между текущим набором и снимком (по именам модов-файлов). */
	public static final class Diff {
		/** Есть сейчас, не было в снимке. */
		public final List<String> added = new ArrayList<>();
		/** Было в снимке, сейчас нет. */
		public final List<String> removed = new ArrayList<>();
		/** Тот же файл (по имени), но другое содержимое. */
		public final List<String> changed = new ArrayList<>();

		public boolean isEmpty() {
			return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
		}
	}

	private ModSnapshots() { }

	// ---------------------------------------------------------------- снимок

	/** Удачный запуск: запомнить набор, если он отличается от последнего снимка. */
	public static void take(Path gameDir, Path modsDir) {
		try {
			List<Snapshot> snaps = list(gameDir);
			Snapshot last = snaps.isEmpty() ? null : snaps.get(0);
			List<Entry> current = current(modsDir, last);

			if (last != null && sameSet(last.entries, current)) {
				return;
			}

			Path store = gameDir.resolve(".argentum").resolve(STORE_DIR);
			Files.createDirectories(store);

			for (Entry e : current) {
				keep(store, modsDir.resolve(e.file), e.sha1);
			}

			long now = System.currentTimeMillis();
			String id = "snap-" + now;
			Path dir = gameDir.resolve(".argentum").resolve(SNAP_DIR);
			Files.createDirectories(dir);
			StringBuilder sb = new StringBuilder("time=").append(now).append('\n');

			for (Entry e : current) {
				sb.append(e.sha1).append('\t').append(e.size).append('\t').append(e.mtime).append('\t').append(e.file).append('\n');
			}

			Files.write(dir.resolve(id + ".txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
			Path index = modsDir.resolve(INDEX_FILE);

			if (Files.isRegularFile(index)) {
				Files.copy(index, dir.resolve(id + ".index.json"), StandardCopyOption.REPLACE_EXISTING);
			}

			prune(gameDir);
			Log.info(LogCategory.GENERAL, "Снимок рабочего набора модов: %d файлов", current.size());
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось сохранить снимок рабочего набора модов: %s", e);
		}
	}

	/** Снимки, самый новый первым. */
	public static List<Snapshot> list(Path gameDir) {
		Path dir = gameDir.resolve(".argentum").resolve(SNAP_DIR);
		List<Snapshot> out = new ArrayList<>();

		if (!Files.isDirectory(dir)) {
			return out;
		}

		try (Stream<Path> files = Files.list(dir)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String name = p.getFileName().toString();

				if (name.startsWith("snap-") && name.endsWith(".txt")) {
					Snapshot s = read(p, name.substring(0, name.length() - 4));

					if (s != null) {
						out.add(s);
					}
				}
			}
		} catch (IOException e) {
			return out;
		}

		out.sort((a, b) -> Long.compare(b.time, a.time));

		return out;
	}

	private static Snapshot read(Path file, String id) {
		try {
			List<Entry> entries = new ArrayList<>();
			long time = 0;

			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.startsWith("time=")) {
					time = Long.parseLong(line.substring(5).trim());
					continue;
				}

				String[] parts = line.split("\t", 4);

				if (parts.length == 4) {
					entries.add(new Entry(parts[3], parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2])));
				}
			}

			return new Snapshot(id, time, entries);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Самый свежий снимок, который отличается от текущего набора, или null. */
	public static Snapshot rollbackTarget(Path gameDir, Path modsDir) {
		List<Snapshot> snaps = list(gameDir);

		if (snaps.isEmpty()) {
			return null;
		}

		try {
			List<Entry> current = current(modsDir, snaps.get(0));

			for (Snapshot s : snaps) {
				if (!sameSet(s.entries, current)) {
					return s;
				}
			}
		} catch (IOException e) {
			return null;
		}

		return null;
	}

	/** Чем текущий набор отличается от снимка. */
	public static Diff diff(Snapshot from, Path modsDir) {
		Diff d = new Diff();

		try {
			List<Entry> current = current(modsDir, from);
			Map<String, Entry> old = new HashMap<>();

			for (Entry e : from.entries) {
				old.put(e.file, e);
			}

			Set<String> oldHashes = from.hashes();
			Set<String> curNames = new HashSet<>();

			for (Entry e : current) {
				curNames.add(e.file);
				Entry o = old.get(e.file);

				if (o == null) {
					if (!oldHashes.contains(e.sha1)) {
						d.added.add(e.file);
					}
				} else if (!o.sha1.equals(e.sha1)) {
					d.changed.add(e.file);
				}
			}

			Set<String> curHashes = new HashSet<>();

			for (Entry e : current) {
				curHashes.add(e.sha1);
			}

			for (Entry e : from.entries) {
				if (!curNames.contains(e.file) && !curHashes.contains(e.sha1)) {
					d.removed.add(e.file);
				}
			}
		} catch (IOException ignored) {
			// пустая разница
		}

		return d;
	}

	// ---------------------------------------------------------------- откат

	public static void requestRollback(Path gameDir) throws IOException {
		Path dir = gameDir.resolve(".argentum");
		Files.createDirectories(dir);
		Files.write(dir.resolve(REQUEST_FILE), "rollback".getBytes(StandardCharsets.UTF_8));
	}

	public static boolean rollbackRequested(Path gameDir) {
		return Files.exists(gameDir.resolve(".argentum").resolve(REQUEST_FILE));
	}

	/** При запуске до поиска модов: выполнить откат, если о нём просили. @return итог для игрока или null */
	public static String applyIfRequested(Path gameDir, Path modsDir) {
		if (!rollbackRequested(gameDir)) {
			return null;
		}

		try {
			Files.deleteIfExists(gameDir.resolve(".argentum").resolve(REQUEST_FILE));
		} catch (IOException ignored) {
			// попробуем ещё раз в следующий раз — откат идемпотентен
		}

		Snapshot target = rollbackTarget(gameDir, modsDir);

		if (target == null) {
			Log.info(LogCategory.GENERAL, "Откат не нужен: нет снимка, отличного от текущего набора модов");
			return null;
		}

		return restore(gameDir, modsDir, target);
	}

	/** Привести {@code mods} к снимку. @return итог для игрока */
	public static String restore(Path gameDir, Path modsDir, Snapshot target) {
		Path store = gameDir.resolve(".argentum").resolve(STORE_DIR);
		int removed = 0;
		int restored = 0;
		int missing = 0;

		try {
			Files.createDirectories(store);
			List<Entry> current = current(modsDir, target);
			Set<String> want = target.hashes();
			Set<String> have = new HashSet<>();

			// лишнее: в хранилище (вдруг понадобится снова) и прочь из mods
			for (Entry e : current) {
				if (want.contains(e.sha1)) {
					have.add(e.sha1);
					continue;
				}

				Path file = modsDir.resolve(e.file);
				keep(store, file, e.sha1);

				try {
					Files.delete(file);
				} catch (IOException busy) {
					PendingRemovals.schedule(modsDir, e.file); // файл держит прошлая копия игры — не грузим и удалим позже
				}

				removed++;
			}

			// недостающее — из хранилища
			for (Entry e : target.entries) {
				if (have.contains(e.sha1)) {
					continue;
				}

				Path src = store.resolve(e.sha1 + ".jar");

				if (!Files.isRegularFile(src)) {
					missing++;
					continue;
				}

				Path dst = modsDir.resolve(e.file);

				if (Files.exists(dst) || PendingRemovals.isPending(e.file)) {
					String base = e.file.endsWith(".jar") ? e.file.substring(0, e.file.length() - 4) : e.file;
					dst = modsDir.resolve(base + "+restored.jar");
				}

				linkOrCopy(src, dst);
				have.add(e.sha1);
				restored++;
			}

			Path index = gameDir.resolve(".argentum").resolve(SNAP_DIR).resolve(target.id + ".index.json");

			if (Files.isRegularFile(index)) {
				Files.copy(index, modsDir.resolve(INDEX_FILE), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Откат набора модов прерван: %s", e);
		}

		String result = target.date() + "|" + removed + "|" + restored + "|" + missing;

		try {
			Files.write(gameDir.resolve(".argentum").resolve(RESULT_FILE), result.getBytes(StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			// итог только для показа
		}

		Log.info(LogCategory.GENERAL, "Откат к набору модов от %s: убрано %d, возвращено %d, не найдено %d", target.date(), removed, restored, missing);

		return result;
	}

	// ---------------------------------------------------------------- служебное

	/** Включённые jar в {@code mods} (без отложенных на удаление); SHA-1 берётся из снимка, если файл не менялся. */
	static List<Entry> current(Path modsDir, Snapshot hint) throws IOException {
		Map<String, Entry> known = new HashMap<>();

		if (hint != null) {
			for (Entry e : hint.entries) {
				known.put(e.file, e);
			}
		}

		List<Entry> out = new ArrayList<>();

		if (!Files.isDirectory(modsDir)) {
			return out;
		}

		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String name = p.getFileName().toString();

				if (!name.endsWith(".jar") || name.startsWith(".") || PendingRemovals.isPending(name) || !Files.isRegularFile(p)) {
					continue;
				}

				long size = Files.size(p);
				long mtime = Files.getLastModifiedTime(p).toMillis();
				Entry k = known.get(name);
				String sha1 = k != null && k.size == size && k.mtime == mtime ? k.sha1 : sha1(p);
				out.add(new Entry(name, sha1, size, mtime));
			}
		}

		out.sort((a, b) -> a.file.compareTo(b.file));

		return out;
	}

	private static boolean sameSet(List<Entry> a, List<Entry> b) {
		if (a.size() != b.size()) {
			return false;
		}

		Map<String, Integer> count = new LinkedHashMap<>();

		for (Entry e : a) {
			count.merge(e.sha1, 1, Integer::sum);
		}

		for (Entry e : b) {
			Integer c = count.get(e.sha1);

			if (c == null || c == 0) {
				return false;
			}

			count.put(e.sha1, c - 1);
		}

		return true;
	}

	private static void keep(Path store, Path file, String sha1) throws IOException {
		Path dst = store.resolve(sha1 + ".jar");

		if (!Files.exists(dst) && Files.isRegularFile(file)) {
			linkOrCopy(file, dst);
		}
	}

	private static void linkOrCopy(Path src, Path dst) throws IOException {
		try {
			Files.createLink(dst, src);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Оставить {@link #KEEP} последних снимков и только нужные им файлы хранилища. */
	private static void prune(Path gameDir) throws IOException {
		List<Snapshot> snaps = list(gameDir);
		Path dir = gameDir.resolve(".argentum").resolve(SNAP_DIR);
		Set<String> used = new HashSet<>();

		for (int i = 0; i < snaps.size(); i++) {
			Snapshot s = snaps.get(i);

			if (i < KEEP) {
				used.addAll(s.hashes());
			} else {
				Files.deleteIfExists(dir.resolve(s.id + ".txt"));
				Files.deleteIfExists(dir.resolve(s.id + ".index.json"));
			}
		}

		Path store = gameDir.resolve(".argentum").resolve(STORE_DIR);

		if (!Files.isDirectory(store)) {
			return;
		}

		try (Stream<Path> files = Files.list(store)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String name = p.getFileName().toString();

				if (name.endsWith(".jar") && !used.contains(name.substring(0, name.length() - 4))) {
					Files.deleteIfExists(p);
				}
			}
		}
	}

	static String sha1(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[65536];
			int n;

			while ((n = in.read(buf)) > 0) {
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

	/** Итог последнего отката (дата|убрано|возвращено|не найдено) и забыть его; null — отката не было. */
	public static String takeResult(Path gameDir) {
		Path f = gameDir.resolve(".argentum").resolve(RESULT_FILE);

		try {
			if (!Files.isRegularFile(f)) {
				return null;
			}

			String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
			Files.deleteIfExists(f);

			return s;
		} catch (IOException e) {
			return null;
		}
	}

	static List<Entry> entries(Snapshot s) {
		return Collections.unmodifiableList(s.entries);
	}
}
