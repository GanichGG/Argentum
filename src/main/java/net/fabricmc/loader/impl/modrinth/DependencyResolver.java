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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Обходит граф обязательных ({@code required}) зависимостей мода на Modrinth и собирает
 * плоский список версий на установку — сам мод плюс всё, что ему требуется и ещё не стоит.
 * Необязательные ({@code optional}) и конфликтующие ({@code incompatible}) зависимости не
 * трогает — это выбор пользователя/задача отдельной проверки, не авто-установки.
 */
public final class DependencyResolver {
	private DependencyResolver() { }

	/** Что именно запрашивать у Modrinth — конкретная версия (если зависимость её пином) или проект (берём свежайшую под нашу игру/лоадер). */
	private static final class Request {
		final String projectIdOrSlug;
		final String versionId;

		Request(String projectIdOrSlug, String versionId) {
			this.projectIdOrSlug = projectIdOrSlug;
			this.versionId = versionId;
		}

		String dedupeKey() {
			return versionId != null ? "v:" + versionId : "p:" + projectIdOrSlug;
		}
	}

	/**
	 * @param rootProjectIdOrSlug корень графа — мод, который пользователь попросил поставить
	 * @param installed            id проектов, уже стоящих (из {@link InstalledModsIndex}) — пропускаются
	 * @return список версий на скачивание, в порядке обхода (корень первым)
	 */
	public static List<ModrinthVersion> resolve(String rootProjectIdOrSlug, String loader, String gameVersion,
			InstalledModsIndex installed) throws ModrinthApiException {
		Map<String, ModrinthVersion> resolved = new LinkedHashMap<>(); // keyed by real projectId
		Deque<Request> queue = new ArrayDeque<>();
		java.util.Set<String> queuedKeys = new java.util.HashSet<>();

		Request rootRequest = new Request(rootProjectIdOrSlug, null);
		queue.add(rootRequest);
		queuedKeys.add(rootRequest.dedupeKey());

		while (!queue.isEmpty()) {
			Request request = queue.poll();

			ModrinthVersion version = fetch(request, loader, gameVersion);

			if (version == null) continue; // зависимость, для которой ничего не нашлось под нашу игру/лоадер — пропускаем, это не required-сбой тут

			if (resolved.containsKey(version.projectId) || installed.contains(version.projectId)) {
				continue;
			}

			resolved.put(version.projectId, version);

			for (ModrinthDependency dep : version.dependencies) {
				if (dep.type != ModrinthDependency.Type.REQUIRED) continue;
				if (dep.projectId == null && dep.versionId == null) continue;
				if (dep.projectId != null && installed.contains(dep.projectId)) continue;
				if (dep.projectId != null && resolved.containsKey(dep.projectId)) continue;

				Request depRequest = new Request(dep.projectId, dep.versionId);

				if (queuedKeys.add(depRequest.dedupeKey())) {
					queue.add(depRequest);
				}
			}
		}

		return new ArrayList<>(resolved.values());
	}

	/**
	 * То же, но корень графа задан конкретной версией (обновление мода или выбор версии): её ставим в любом случае,
	 * даже если проект уже есть в реестре, а зависимости — только недостающие.
	 */
	public static List<ModrinthVersion> resolveFor(ModrinthVersion root, String loader, String gameVersion, InstalledModsIndex installed) throws ModrinthApiException {
		Map<String, ModrinthVersion> resolved = new LinkedHashMap<>();
		Deque<Request> queue = new ArrayDeque<>();
		java.util.Set<String> queuedKeys = new java.util.HashSet<>();

		resolved.put(root.projectId, root);
		enqueueDependencies(root, installed, resolved, queue, queuedKeys);

		while (!queue.isEmpty()) {
			ModrinthVersion version = fetch(queue.poll(), loader, gameVersion);

			if (version == null || resolved.containsKey(version.projectId) || installed.contains(version.projectId)) {
				continue;
			}

			resolved.put(version.projectId, version);
			enqueueDependencies(version, installed, resolved, queue, queuedKeys);
		}

		// зависимости ставятся раньше самого мода
		List<ModrinthVersion> ordered = new ArrayList<>(resolved.values());
		ordered.remove(root);
		ordered.add(root);

		return ordered;
	}

	private static void enqueueDependencies(ModrinthVersion version, InstalledModsIndex installed, Map<String, ModrinthVersion> resolved,
			Deque<Request> queue, java.util.Set<String> queuedKeys) {
		for (ModrinthDependency dep : version.dependencies) {
			if (dep.type != ModrinthDependency.Type.REQUIRED) continue;
			if (dep.projectId == null && dep.versionId == null) continue;
			if (dep.projectId != null && (installed.contains(dep.projectId) || resolved.containsKey(dep.projectId))) continue;

			Request request = new Request(dep.projectId, dep.versionId);

			if (queuedKeys.add(request.dedupeKey())) {
				queue.add(request);
			}
		}
	}

	private static ModrinthVersion fetch(Request request, String loader, String gameVersion) throws ModrinthApiException {
		if (request.versionId != null) {
			return ModrinthClient.getVersion(request.versionId);
		}

		List<ModrinthVersion> versions = ModrinthClient.getProjectVersions(request.projectIdOrSlug, loader, gameVersion);

		if (versions.isEmpty()) {
			Log.warn(LogCategory.MODRINTH, "У мода \"%s\" на Modrinth нет версии под %s/%s — пропускаю", request.projectIdOrSlug, gameVersion, loader);
			return null;
		}

		return versions.get(0); // modrinth отдаёт новейшую первой
	}
}
