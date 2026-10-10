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
