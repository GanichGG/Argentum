/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.modrinth;

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
