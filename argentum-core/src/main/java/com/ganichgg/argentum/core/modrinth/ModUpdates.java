/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.modrinth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ganichgg.argentum.core.files.PendingRemovals;
import com.ganichgg.argentum.core.i18n.Lang;

/**
 * Обновление и смена версии установленных модов: проверка новых версий по SHA-1 файлов и замена файла.
 * Новый файл ставится рядом, старый удаляется (если его держит игра — при следующем запуске).
 */
public final class ModUpdates {
	private ModUpdates() { }

	/** projectId -> новейшая совместимая версия, если она отличается от установленной. */
	public static Map<String, ModrinthVersion> check(Path modsDir, InstalledModsIndex index, String loader, String gameVersion) throws ModrinthApiException {
		Map<String, LocalMods.Mod> byFile = new LinkedHashMap<>();

		for (LocalMods.Mod m : LocalMods.scan(modsDir)) {
			byFile.put(m.fileName, m);
		}

		Map<String, String> hashToProject = new LinkedHashMap<>();

		for (Map.Entry<String, InstalledModsIndex.Entry> e : index.entries().entrySet()) {
			LocalMods.Mod m = byFile.get(e.getValue().fileName);

			if (m != null && !e.getKey().startsWith(LocalMods.LOCAL_PREFIX)) {
				hashToProject.put(m.sha1, e.getKey());
			}
		}

		Map<String, ModrinthVersion> latest = ModrinthClient.latestForHashes(new ArrayList<>(hashToProject.keySet()), loader, gameVersion);
		Map<String, ModrinthVersion> updates = new LinkedHashMap<>();

		for (Map.Entry<String, ModrinthVersion> e : latest.entrySet()) {
			String projectId = hashToProject.get(e.getKey());
			InstalledModsIndex.Entry current = projectId == null ? null : index.entries().get(projectId);

			if (current != null && e.getValue().id != null && !e.getValue().id.equals(current.versionId)) {
				updates.put(projectId, e.getValue());
			}
		}

		return updates;
	}

	/** Результат первой половины замены: новые файлы уже скачаны, старый ещё на месте. */
	public static final class Staged {
		public final ModInstaller.InstallResult result;
		/** Имя старого файла этого мода (null — мод не был установлен). */
		public final String oldFile;
		/** Имя нового основного файла мода. */
		public final String newFile;
		public final boolean wasDisabled;

		Staged(ModInstaller.InstallResult result, String oldFile, String newFile, boolean wasDisabled) {
			this.result = result;
			this.oldFile = oldFile;
			this.newFile = newFile;
			this.wasDisabled = wasDisabled;
		}
	}

	/**
	 * Первая половина замены: скачивает указанную версию (с недостающими зависимостями) рядом со старым файлом, ничего
	 * не удаляя и не отключая. Так набор можно проверить, а при неудаче откатить, не потеряв установленное.
	 */
	public static Staged stage(Path modsDir, ModrinthVersion target, String loader, String gameVersion, ModInstaller.Progress progress) throws ModrinthApiException {
		InstalledModsIndex index = InstalledModsIndex.load(modsDir);
		InstalledModsIndex.Entry old = index.entries().get(target.projectId);
		List<ModrinthVersion> plan = DependencyResolver.resolveFor(target, loader, gameVersion, index);
		Set<String> avoid = new HashSet<>();
		boolean wasDisabled = false;

		if (old != null) {
			avoid.add(old.fileName); // его может держать игра — новый файл должен получить другое имя
			wasDisabled = LocalMods.state(modsDir, old.fileName) == LocalMods.State.DISABLED;
		}

		ModInstaller.InstallResult result = ModInstaller.install(modsDir, plan, progress, avoid);
		InstalledModsIndex.Entry fresh = InstalledModsIndex.load(modsDir).entries().get(target.projectId);

		return new Staged(result, old != null ? old.fileName : null, fresh != null ? fresh.fileName : null, wasDisabled);
	}

	/** Вторая половина замены: убирает старый файл и возвращает «отключён», если мод был отключён. */
	public static void finish(Path modsDir, Staged staged) throws ModrinthApiException {
		if (staged.oldFile != null && (staged.newFile == null || !staged.newFile.equals(staged.oldFile))) {
			removeFile(modsDir, staged.oldFile);
		}

		if (staged.wasDisabled && staged.newFile != null) {
			try {
				LocalMods.disable(modsDir, staged.newFile);
			} catch (IOException e) {
				throw new ModrinthApiException(Lang.tr("Мод обновлён, но снова отключить его не удалось: ") + e.getMessage(), e);
			}
		}
	}

	/**
	 * Ставит указанную версию (с недостающими зависимостями) вместо установленной и убирает старый файл.
	 *
	 * @return итог установки; файлы — в {@code installedFiles}
	 */
	public static ModInstaller.InstallResult apply(Path modsDir, ModrinthVersion target, String loader, String gameVersion,
			ModInstaller.Progress progress) throws ModrinthApiException {
		Staged staged = stage(modsDir, target, loader, gameVersion, progress);
		finish(modsDir, staged);

		return staged.result;
	}

	/** Убирает файл мода (включённый или отключённый вид); занятый игрой — при следующем запуске. */
	public static void removeFile(Path modsDir, String fileName) {
		for (String name : new String[] {fileName, fileName + LocalMods.DISABLED_SUFFIX}) {
			Path p = modsDir.resolve(name);

			try {
				Files.deleteIfExists(p);
			} catch (IOException e) {
				try {
					PendingRemovals.schedule(modsDir, name);
				} catch (IOException ignored) {
					// не удалось даже записать — файл останется, мод просто не исчезнет
				}
			}
		}
	}

	/** Версии мода под текущую игру для окна выбора: новейшие первыми (как отдаёт Modrinth). */
	public static List<ModrinthVersion> versions(String projectId, String loader, String gameVersion) throws ModrinthApiException {
		return Collections.unmodifiableList(ModrinthClient.getProjectVersions(projectId, loader, gameVersion));
	}
}
