/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.mods;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ModJarTest {
	@TempDir
	Path dir;

	private Path jar(String name, String... entries) throws IOException {
		Path p = this.dir.resolve(name);

		try (OutputStream os = Files.newOutputStream(p); ZipOutputStream zip = new ZipOutputStream(os)) {
			for (int i = 0; i < entries.length; i += 2) {
				zip.putNextEntry(new ZipEntry(entries[i]));
				zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}

		return p;
	}

	@Test
	public void fabric() throws Exception {
		ModJar m = ModJar.read(jar("modmenu.jar", "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"modmenu\",\"name\":\"Mod Menu\",\"version\":\"11.0.3\",\"depends\":{\"fabricloader\":\">=0.15\"}}"));
		Assertions.assertEquals(ModJar.Kind.FABRIC, m.kind);
		Assertions.assertEquals("modmenu", m.mods.get(0).id);
		Assertions.assertEquals("Mod Menu", m.mods.get(0).name);
		Assertions.assertEquals("11.0.3", m.mods.get(0).version);
	}

	@Test
	public void neoForgeWithJarVersionAndSeveralMods() throws Exception {
		String toml = "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n\n[[mods]]\nmodId=\"create\"\nversion=\"${file.jarVersion}\"\n"
				+ "displayName=\"Create\"\n\n[[dependencies.create]]\nmodId=\"neoforge\"\ntype=\"required\"\nversionRange=\"[21.1,)\"\n\n"
				+ "[[mods]]\nmodId=\"create_extra\"\nversion=\"1.0\"\n";
		ModJar m = ModJar.read(jar("create.jar", "META-INF/neoforge.mods.toml", toml, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 6.0.10\n"));
		Assertions.assertEquals(ModJar.Kind.NEOFORGE, m.kind);
		Assertions.assertEquals(2, m.mods.size(), "dependency blocks are not mods");
		Assertions.assertEquals("create", m.mods.get(0).id);
		Assertions.assertEquals("6.0.10", m.mods.get(0).version);
		Assertions.assertEquals("create_extra", m.mods.get(1).id);
	}

	@Test
	public void forgeIsModsTomlWithoutNeoForge() throws Exception {
		ModJar m = ModJar.read(jar("jei-forge.jar", "META-INF/mods.toml", "[[mods]] #mandatory\nmodId=\"jei\" #mandatory\nversion=\"19.0\"\n"));
		Assertions.assertEquals(ModJar.Kind.FORGE, m.kind);
		Assertions.assertEquals("jei", m.mods.get(0).id);
	}

	@Test
	public void multiLoaderJarPrefersNeoForgeAndRemembersTheRest() throws Exception {
		ModJar m = ModJar.read(jar("both.jar", "META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"both\"\nversion=\"1\"\n",
				"fabric.mod.json", "{\"id\":\"both\",\"version\":\"1\"}"));
		Assertions.assertEquals(ModJar.Kind.NEOFORGE, m.kind);
		Assertions.assertTrue(m.alsoHas.contains(ModJar.Kind.FABRIC));
	}

	@Test
	public void plainLibraryAndGarbageAreUnknown() throws Exception {
		Assertions.assertEquals(ModJar.Kind.UNKNOWN, ModJar.read(jar("lib.jar", "a/B.class", "x")).kind);
		Path junk = this.dir.resolve("junk.jar");
		Files.write(junk, "not a zip".getBytes(StandardCharsets.UTF_8));
		Assertions.assertEquals(ModJar.Kind.UNKNOWN, ModJar.read(junk).kind);
	}
}
