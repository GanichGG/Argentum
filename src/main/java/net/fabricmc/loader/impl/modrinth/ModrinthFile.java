/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl.modrinth;

/** One downloadable file of a {@link ModrinthVersion} (modrinth's "files" array entry). */
public final class ModrinthFile {
	public final String url;
	public final String filename;
	public final boolean primary;
	/** SHA-1 hash of the file, lowercase hex, null if modrinth didn't provide one. */
	public final String sha1;

	public ModrinthFile(String url, String filename, boolean primary, String sha1) {
		this.url = url;
		this.filename = filename;
		this.primary = primary;
		this.sha1 = sha1;
	}
}
