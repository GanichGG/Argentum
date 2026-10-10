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

import java.util.List;

/** A single file version of a mod project on Modrinth (one element of {@code GET /v2/project/{id}/version}). */
public final class ModrinthVersion {
	public final String id;
	public final String projectId;
	public final String versionNumber;
	public final String name;
	public final List<String> gameVersions;
	public final List<String> loaders;
	public final List<ModrinthDependency> dependencies;
	public final List<ModrinthFile> files;

	public ModrinthVersion(String id, String projectId, String versionNumber, String name,
			List<String> gameVersions, List<String> loaders,
			List<ModrinthDependency> dependencies, List<ModrinthFile> files) {
		this.id = id;
		this.projectId = projectId;
		this.versionNumber = versionNumber;
		this.name = name;
		this.gameVersions = gameVersions;
		this.loaders = loaders;
		this.dependencies = dependencies;
		this.files = files;
	}

	/** The file to actually download — the one modrinth marked primary, or the first one if none is. */
	public ModrinthFile getPrimaryFile() {
		for (ModrinthFile file : files) {
			if (file.primary) return file;
		}

		return files.isEmpty() ? null : files.get(0);
	}
}
