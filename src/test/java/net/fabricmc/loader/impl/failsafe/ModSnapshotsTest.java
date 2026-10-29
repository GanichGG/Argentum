/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.failsafe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ModSnapshotsTest {
	@TempDir
	Path game;

	private Path mods() throws IOException {
		return Files.createDirectories(game.resolve("mods"));
	}

	private static void jar(Path mods, String name, String content) throws IOException {
		Path f = mods.resolve(name);
		Files.write(f, content.getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - content.hashCode() % 100000));
	}

	@Test
	public void updateThenRollBack() throws Exception {
		Path mods = mods();
		jar(mods, "sodium-0.5.jar", "sodium old");
		jar(mods, "iris-1.7.jar", "iris");
		Files.write(mods.resolve(".argentum-modrinth.json"), "{\"old\":true}".getBytes(StandardCharsets.UTF_8));

		ModSnapshots.take(game, mods);
		Assertions.assertEquals(1, ModSnapshots.list(game).size());
		Assertions.assertNull(ModSnapshots.rollbackTarget(game, mods), "the current set is the snapshot itself");

		// «обновил мод»: старый файл удалён, новый положен, добавлен ещё один мод
		Files.delete(mods.resolve("sodium-0.5.jar"));
		jar(mods, "sodium-0.6.jar", "sodium new");
		jar(mods, "broken.jar", "broken");
		Files.write(mods.resolve(".argentum-modrinth.json"), "{\"new\":true}".getBytes(StandardCharsets.UTF_8));

		ModSnapshots.Snapshot target = ModSnapshots.rollbackTarget(game, mods);
		Assertions.assertNotNull(target);
		ModSnapshots.Diff d = ModSnapshots.diff(target, mods);
		Assertions.assertTrue(d.added.contains("sodium-0.6.jar"));
		Assertions.assertTrue(d.added.contains("broken.jar"));
		Assertions.assertTrue(d.removed.contains("sodium-0.5.jar"));

		ModSnapshots.requestRollback(game);
		Assertions.assertNotNull(ModSnapshots.applyIfRequested(game, mods));
		Assertions.assertFalse(ModSnapshots.rollbackRequested(game));

		Assertions.assertTrue(Files.exists(mods.resolve("sodium-0.5.jar")), "old version is back from the store");
		Assertions.assertEquals("sodium old", new String(Files.readAllBytes(mods.resolve("sodium-0.5.jar")), StandardCharsets.UTF_8));
		Assertions.assertTrue(Files.exists(mods.resolve("iris-1.7.jar")));
		Assertions.assertFalse(Files.exists(mods.resolve("sodium-0.6.jar")));
		Assertions.assertFalse(Files.exists(mods.resolve("broken.jar")));
		Assertions.assertEquals("{\"old\":true}", new String(Files.readAllBytes(mods.resolve(".argentum-modrinth.json")), StandardCharsets.UTF_8));
		Assertions.assertNull(ModSnapshots.rollbackTarget(game, mods));
	}

	@Test
	public void sameSetIsNotSavedTwiceAndOldOnesArePruned() throws Exception {
		Path mods = mods();
		jar(mods, "a.jar", "a");
		ModSnapshots.take(game, mods);
		ModSnapshots.take(game, mods);
		Assertions.assertEquals(1, ModSnapshots.list(game).size());

		for (int i = 0; i < 5; i++) {
			jar(mods, "extra" + i + ".jar", "extra" + i);
			Thread.sleep(5);
			ModSnapshots.take(game, mods);
		}

		Assertions.assertEquals(3, ModSnapshots.list(game).size());
	}

	@Test
	public void noRequestNoRollback() throws Exception {
		Path mods = mods();
		jar(mods, "a.jar", "a");
		Assertions.assertNull(ModSnapshots.applyIfRequested(game, mods));
	}
}
