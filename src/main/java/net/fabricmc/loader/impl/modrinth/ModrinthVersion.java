/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
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
	/** release / beta / alpha (как отдаёт Modrinth); null, если не указано. */
	public final String versionType;
	/** Дата публикации, ISO-8601; null, если не указана. */
	public final String datePublished;

	public ModrinthVersion(String id, String projectId, String versionNumber, String name,
			List<String> gameVersions, List<String> loaders,
			List<ModrinthDependency> dependencies, List<ModrinthFile> files) {
		this(id, projectId, versionNumber, name, gameVersions, loaders, dependencies, files, null, null);
	}

	public ModrinthVersion(String id, String projectId, String versionNumber, String name,
			List<String> gameVersions, List<String> loaders,
			List<ModrinthDependency> dependencies, List<ModrinthFile> files, String versionType, String datePublished) {
		this.id = id;
		this.projectId = projectId;
		this.versionNumber = versionNumber;
		this.name = name;
		this.gameVersions = gameVersions;
		this.loaders = loaders;
		this.dependencies = dependencies;
		this.files = files;
		this.versionType = versionType;
		this.datePublished = datePublished;
	}

	/** The file to actually download — the one modrinth marked primary, or the first one if none is. */
	public ModrinthFile getPrimaryFile() {
		for (ModrinthFile file : files) {
			if (file.primary) return file;
		}

		return files.isEmpty() ? null : files.get(0);
	}
}
