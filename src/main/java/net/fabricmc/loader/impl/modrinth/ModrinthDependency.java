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

/** One entry of a {@link ModrinthVersion}'s "dependencies" array. */
public final class ModrinthDependency {
	public enum Type {
		REQUIRED, OPTIONAL, INCOMPATIBLE, EMBEDDED, UNKNOWN;

		static Type parse(String raw) {
			if (raw == null) return UNKNOWN;

			switch (raw) {
			case "required": return REQUIRED;
			case "optional": return OPTIONAL;
			case "incompatible": return INCOMPATIBLE;
			case "embedded": return EMBEDDED;
			default: return UNKNOWN;
			}
		}
	}

	/** May be null if modrinth only gave a project id (common for "any version of this project"). */
	public final String versionId;
	/** May be null if modrinth only gave a version id. */
	public final String projectId;
	public final Type type;

	public ModrinthDependency(String versionId, String projectId, Type type) {
		this.versionId = versionId;
		this.projectId = projectId;
		this.type = type;
	}
}
