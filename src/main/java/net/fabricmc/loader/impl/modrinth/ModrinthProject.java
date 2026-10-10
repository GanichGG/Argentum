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

/** A mod project on Modrinth, as returned by search or {@code GET /v2/project/{id}}. */
public final class ModrinthProject {
	public final String id;
	public final String slug;
	public final String title;
	/** Short description (modrinth's "description" field — the one shown on project cards). */
	public final String description;
	/** Null for projects fetched by id: modrinth only gives the author through the team endpoint. */
	public final String author;
	public final long downloads;
	public final long follows;
	public final String iconUrl;
	/** ISO-8601 timestamp of the last update, null if unknown. */
	public final String dateModified;
	public final List<String> categories;
	/** "required", "optional", "unsupported" or null. */
	public final String clientSide;
	public final String serverSide;
	/** Основной цвет иконки (0xRRGGBB) — для плейсхолдера, пока иконка не загружена; 0, если неизвестен. */
	public final int color;

	public ModrinthProject(String id, String slug, String title, String description, String author,
			long downloads, long follows, String iconUrl, String dateModified,
			List<String> categories, String clientSide, String serverSide, int color) {
		this.id = id;
		this.slug = slug;
		this.title = title;
		this.description = description;
		this.author = author;
		this.downloads = downloads;
		this.follows = follows;
		this.iconUrl = iconUrl;
		this.dateModified = dateModified;
		this.categories = categories;
		this.clientSide = clientSide;
		this.serverSide = serverSide;
		this.color = color;
	}
}
