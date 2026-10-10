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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.impl.hotload.PendingRemovals;

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

	/**
	 * Ставит указанную версию (с недостающими зависимостями) вместо установленной и убирает старый файл.
	 *
	 * @return итог установки; файлы — в {@code installedFiles}
	 */
	public static ModInstaller.InstallResult apply(Path modsDir, ModrinthVersion target, String loader, String gameVersion,
			ModInstaller.Progress progress) throws ModrinthApiException {
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

		if (old != null && (fresh == null || !fresh.fileName.equals(old.fileName))) {
			removeFile(modsDir, old.fileName);
		}

		if (wasDisabled && fresh != null) {
			try {
				LocalMods.disable(modsDir, fresh.fileName);
			} catch (IOException e) {
				throw new ModrinthApiException("Мод обновлён, но снова отключить его не удалось: " + e.getMessage(), e);
			}
		}

		return result;
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
