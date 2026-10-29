/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.test.modrinth;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.ganichgg.argentum.core.modrinth.DependencyResolver;
import com.ganichgg.argentum.core.modrinth.InstalledModsIndex;
import com.ganichgg.argentum.core.modrinth.ModInstaller;
import com.ganichgg.argentum.core.modrinth.ModrinthApiException;
import com.ganichgg.argentum.core.modrinth.ModrinthProject;
import com.ganichgg.argentum.core.modrinth.ModrinthVersion;
import com.ganichgg.argentum.core.modrinth.ModrinthClient;

/**
 * Ручной прогон движка автоустановки модов с Modrinth — пока нет экрана в игре.
 *
 * <p>Без установки (только план): {@code <slug> <modsDir>}<br>
 * С установкой: {@code <slug> <modsDir> --install}
 *
 * <p>Пример: {@code fabric-api "C:\...\PrismLauncher\instances\1.21.1\mods"}
 */
public final class ModrinthSmokeTest {
	private static final String LOADER = "fabric";
	private static final String GAME_VERSION = "1.21.1";

	private ModrinthSmokeTest() { }

	public static void main(String[] args) throws ModrinthApiException {
		if (args.length < 2) {
			System.out.println("Использование: <slug-или-id-мода> <путь-к-mods> [--install]");
			System.out.println("Пример: fabric-api \"C:\\...\\PrismLauncher\\instances\\1.21.1\\mods\"");
			return;
		}

		String slug = args[0];
		Path modsDir = Paths.get(args[1]);
		boolean doInstall = args.length > 2 && args[2].equals("--install");

		ModrinthProject project = ModrinthClient.getProject(slug);
		System.out.println("Мод: " + project.title + " (" + project.id + ")");
		System.out.println(project.description);
		System.out.println();

		InstalledModsIndex index = InstalledModsIndex.load(modsDir);
		List<ModrinthVersion> plan = DependencyResolver.resolve(slug, LOADER, GAME_VERSION, index);

		if (plan.isEmpty()) {
			System.out.println("Нечего ставить — всё уже есть, либо для " + GAME_VERSION + "/" + LOADER + " версий не нашлось.");
			return;
		}

		System.out.println("План установки (" + plan.size() + "):");

		for (ModrinthVersion v : plan) {
			System.out.println(" - " + v.projectId + " -> " + v.name + " (" + v.versionNumber + ")");
		}

		if (!doInstall) {
			System.out.println();
			System.out.println("Это dry-run. Добавь --install третьим аргументом, чтобы реально скачать.");
			return;
		}

		System.out.println();
		ModInstaller.InstallResult result = ModInstaller.install(modsDir, plan);
		System.out.println("Установлено в " + result.modsDir + ":");

		for (String name : result.installedModNames) {
			System.out.println(" - " + name);
		}
	}
}
