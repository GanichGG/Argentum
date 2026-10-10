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

/** A mod project on Modrinth, as returned by search or {@code GET /v2/project/{id}}. */
public final class ModrinthProject {
	public final String id;
	public final String slug;
	public final String title;
	public final String description;

	public ModrinthProject(String id, String slug, String title, String description) {
		this.id = id;
		this.slug = slug;
		this.title = title;
		this.description = description;
	}
}
