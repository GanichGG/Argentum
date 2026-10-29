/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.modrinth;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ganichgg.argentum.core.files.PendingRemovals;

/** Ядро менеджера модов без сети: файлы модов, реестр, отложенные операции, защита импорта сборок, разбор отчётов о падении. */
public class ModrinthCoreTest {
	/** Минимальный jar мода: только fabric.mod.json с заданными id, названием и версией. */
	private static Path modJar(Path dir, String fileName, String id, String name, String version) throws IOException {
		Path jar = dir.resolve(fileName);

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"name\":\"" + name + "\"}";
			zip.write(json.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		return jar;
	}

	private static Path zip(Path file, String[][] entries) throws IOException {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (String[] e : entries) {
				zip.putNextEntry(new ZipEntry(e[0]));
				zip.write(e[1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}

		return file;
	}

	// ---------------------------------------------------------------- файлы модов

	@Test
	public void scanReadsMetadataFromEnabledAndDisabledJars(@TempDir Path mods) throws IOException {
		modJar(mods, "alpha-1.0.jar", "alpha", "Alpha", "1.0");
		Files.move(modJar(mods, "beta-2.0.jar", "beta", "Beta", "2.0"), mods.resolve("beta-2.0.jar.disabled"));
		Files.write(mods.resolve("readme.txt"), new byte[] {1});

		List<LocalMods.Mod> found = LocalMods.scan(mods);

		Assertions.assertEquals(2, found.size());
		LocalMods.Mod alpha = LocalMods.find(mods, "alpha-1.0.jar");
		LocalMods.Mod beta = LocalMods.find(mods, "beta-2.0.jar");
		Assertions.assertNotNull(alpha);
		Assertions.assertEquals("alpha", alpha.id);
		Assertions.assertTrue(alpha.enabled);
		Assertions.assertNotNull(beta);
		Assertions.assertFalse(beta.enabled);
		Assertions.assertEquals(40, alpha.sha1.length());
	}

	@Test
	public void disableAndEnableRenameTheJar(@TempDir Path mods) throws IOException {
		modJar(mods, "gamma-1.jar", "gamma", "Gamma", "1");

		Assertions.assertTrue(LocalMods.disable(mods, "gamma-1.jar"));
		Assertions.assertFalse(Files.exists(mods.resolve("gamma-1.jar")));
		Assertions.assertEquals(LocalMods.State.DISABLED, LocalMods.state(mods, "gamma-1.jar"));

		LocalMods.enable(mods, "gamma-1.jar");
		Assertions.assertEquals(LocalMods.State.ENABLED, LocalMods.state(mods, "gamma-1.jar"));
		Assertions.assertEquals(LocalMods.State.MISSING, LocalMods.state(mods, "nothing.jar"));
	}

	/** Регрессия: удалённый мод, чей файл ещё держит игра, не должен снова появляться в списке. */
	@Test
	public void jarWaitingForDeletionIsNotListed(@TempDir Path mods) throws IOException {
		modJar(mods, "stuck-unit-test.jar", "stuck", "Stuck", "1");
		Assertions.assertEquals(1, LocalMods.scan(mods).size());

		PendingRemovals.schedule(mods, "stuck-unit-test.jar");

		try {
			Assertions.assertTrue(PendingRemovals.isPending("stuck-unit-test.jar"));
			Assertions.assertEquals(0, LocalMods.scan(mods).size());
		} finally {
			PendingRemovals.cancel(mods, "stuck-unit-test.jar");
		}
	}

	@Test
	public void pendingOperationsRunAtNextStart(@TempDir Path mods) throws IOException {
		Path doomed = modJar(mods, "doomed-unit-test.jar", "doomed", "Doomed", "1");
		Path sleepy = modJar(mods, "sleepy-unit-test.jar", "sleepy", "Sleepy", "1");

		PendingRemovals.schedule(mods, "doomed-unit-test.jar");
		PendingRemovals.scheduleDisable(mods, "sleepy-unit-test.jar");
		Assertions.assertTrue(PendingRemovals.isPendingDisable("sleepy-unit-test.jar"));

		PendingRemovals.apply(mods); // «следующий запуск»

		Assertions.assertFalse(Files.exists(doomed));
		Assertions.assertFalse(Files.exists(sleepy));
		Assertions.assertTrue(Files.exists(mods.resolve("sleepy-unit-test.jar.disabled")));
		Assertions.assertFalse(PendingRemovals.isPending("doomed-unit-test.jar"));
		Assertions.assertFalse(PendingRemovals.isPending("sleepy-unit-test.jar"));
	}

	@Test
	public void pendingOperationsRefuseNamesWithPaths(@TempDir Path mods) {
		Assertions.assertThrows(IOException.class, () -> PendingRemovals.schedule(mods, "../evil.jar"));
		Assertions.assertThrows(IOException.class, () -> PendingRemovals.schedule(mods, "a/b.jar"));
	}

	// ---------------------------------------------------------------- реестр установленных

	@Test
	public void indexRoundTripsAndRestoresFromSnapshot(@TempDir Path mods) throws IOException {
		InstalledModsIndex index = InstalledModsIndex.load(mods);
		index.put("AANobbMI", "v1", "sodium.jar");
		index.save();

		byte[] snapshot = InstalledModsIndex.snapshot(mods);
		Assertions.assertNotNull(snapshot);

		InstalledModsIndex changed = InstalledModsIndex.load(mods);
		changed.put("P7dR8mSH", "v2", "fabric-api.jar");
		changed.remove("AANobbMI");
		changed.save();
		Assertions.assertEquals(1, InstalledModsIndex.load(mods).entries().size());
		Assertions.assertTrue(InstalledModsIndex.load(mods).contains("P7dR8mSH"));

		InstalledModsIndex.restore(mods, snapshot); // откат неудачного применения

		InstalledModsIndex restored = InstalledModsIndex.load(mods);
		Assertions.assertTrue(restored.contains("AANobbMI"));
		Assertions.assertFalse(restored.contains("P7dR8mSH"));
		Assertions.assertEquals("sodium.jar", restored.entries().get("AANobbMI").fileName);

		InstalledModsIndex.restore(mods, null); // снимка не было — реестра тоже не должно быть
		Assertions.assertEquals(0, InstalledModsIndex.load(mods).entries().size());
	}

	// ---------------------------------------------------------------- импорт сборок

	private static String index(String path, String url, String minecraft) {
		return "{\"formatVersion\":1,\"game\":\"minecraft\",\"versionId\":\"1\",\"name\":\"Test pack\","
				+ "\"dependencies\":{\"minecraft\":\"" + minecraft + "\",\"fabric-loader\":\"0.16.0\"},"
				+ "\"files\":[{\"path\":\"" + path + "\",\"hashes\":{\"sha1\":\"0000000000000000000000000000000000000000\","
				+ "\"sha512\":\"00\"},\"downloads\":[\"" + url + "\"],\"fileSize\":1}]}";
	}

	@Test
	public void importRefusesFilesOutsideTheGameFolder(@TempDir Path tmp) throws IOException {
		Path game = Files.createDirectories(tmp.resolve("game"));
		Path pack = zip(tmp.resolve("evil.mrpack"), new String[][] {{"modrinth.index.json", index("mods/../../evil.jar", "https://cdn.modrinth.com/x.jar", "1.21.1")}});

		Assertions.assertThrows(IOException.class, () -> MrpackImporter.importPack(pack, game, "1.21.1", null));
		Assertions.assertFalse(Files.exists(tmp.resolve("evil.jar")));
	}

	@Test
	public void importRefusesForeignFolders(@TempDir Path tmp) throws IOException {
		Path game = Files.createDirectories(tmp.resolve("game"));
		Path pack = zip(tmp.resolve("folders.mrpack"), new String[][] {{"modrinth.index.json", index("options.txt", "https://cdn.modrinth.com/x.txt", "1.21.1")}});

		Assertions.assertThrows(IOException.class, () -> MrpackImporter.importPack(pack, game, "1.21.1", null));
		Assertions.assertFalse(Files.exists(game.resolve("options.txt")));
	}

	@Test
	public void importRefusesUnknownHosts(@TempDir Path tmp) throws IOException {
		Path game = Files.createDirectories(tmp.resolve("game"));
		Path pack = zip(tmp.resolve("host.mrpack"), new String[][] {{"modrinth.index.json", index("mods/a.jar", "https://evil.example.com/a.jar", "1.21.1")}});

		Assertions.assertThrows(IOException.class, () -> MrpackImporter.importPack(pack, game, "1.21.1", null));
		Assertions.assertFalse(Files.exists(game.resolve("mods").resolve("a.jar")));
	}

	@Test
	public void importRefusesPacksForAnotherGameVersion(@TempDir Path tmp) throws IOException {
		Path game = Files.createDirectories(tmp.resolve("game"));
		Path pack = zip(tmp.resolve("version.mrpack"), new String[][] {{"modrinth.index.json", index("mods/a.jar", "https://cdn.modrinth.com/a.jar", "1.20.1")}});

		Assertions.assertThrows(IOException.class, () -> MrpackImporter.importPack(pack, game, "1.21.1", null));
	}

	@Test
	public void importReadsPackDescription(@TempDir Path tmp) throws IOException {
		Path pack = zip(tmp.resolve("ok.mrpack"), new String[][] {{"modrinth.index.json", index("mods/a.jar", "https://cdn.modrinth.com/a.jar", "1.21.1")}});

		MrpackImporter.Pack info = MrpackImporter.read(pack);
		Assertions.assertEquals("Test pack", info.name);
		Assertions.assertEquals("1.21.1", info.minecraft);
		Assertions.assertEquals(1, info.fileCount());
	}

	// ---------------------------------------------------------------- отчёт о падении

	@Test
	public void crashAnalysisNamesTheModFromMixinErrors(@TempDir Path mods) throws IOException {
		modJar(mods, "sodium-0.8.jar", "sodium", "Sodium", "0.8");
		modJar(mods, "lithium-0.15.jar", "lithium", "Lithium", "0.15");
		String report = "---- Minecraft Crash Report ----\nDescription: Rendering overlay\n\n"
				+ "java.lang.RuntimeException: Mixin apply for mod sodium failed sodium.mixins.json:core.Foo from mod sodium -> net.minecraft.Bar\n"
				+ "\tat net.caffeinemc.mods.sodium.client.render.Thing.render(Thing.java:10)\n";

		List<CrashDiagnoser.Suspect> suspects = CrashDiagnoser.analyze(report, mods);

		Assertions.assertFalse(suspects.isEmpty());
		Assertions.assertEquals("sodium", suspects.get(0).modId);
		Assertions.assertEquals("sodium-0.8.jar", suspects.get(0).fileName);
	}
}
