/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.installer.anchor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Куда можно установить ArgentumLoader: инстансы Prism Launcher, обычная папка .minecraft официального лаунчера или любая
 * папка, которую выбрал игрок. Здесь только поиск и разбор папок (без окон), чтобы это можно было проверять без экрана.
 */
final class InstallTargets {
	/** Одна цель установки. */
	static final class Target {
		/** Подпись в списке. */
		final String label;
		/** Корень инстанса Prism (где mmc-pack.json) либо папка игры официального лаунчера. */
		final Path dir;
		final boolean prism;
		/** Версия Minecraft инстанса Prism (из mmc-pack.json); у обычной папки null. */
		final String minecraftVersion;
		/** Что мешает установке (пустая строка — ничего). Установка заблокирована. */
		final String problem;
		/** О чём стоит предупредить, но установка возможна. */
		final String warning;

		Target(String label, Path dir, boolean prism, String minecraftVersion, String problem, String warning) {
			this.label = label;
			this.dir = dir;
			this.prism = prism;
			this.minecraftVersion = minecraftVersion;
			this.problem = problem;
			this.warning = warning;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private InstallTargets() { }

	/** Все найденные цели: инстансы Prism, затем .minecraft официального лаунчера. */
	static List<Target> detect(Path appData) {
		List<Target> found = new ArrayList<>();

		if (appData == null) {
			return found;
		}

		Path prismRoot = appData.resolve("PrismLauncher");
		Path instances = prismRoot.resolve("instances");
		Path configured = configuredInstanceDir(prismRoot);

		if (configured != null) {
			instances = configured;
		}

		if (Files.isDirectory(instances)) {
			List<Path> dirs = new ArrayList<>();

			try (DirectoryStream<Path> stream = Files.newDirectoryStream(instances)) {
				for (Path p : stream) {
					if (Files.isDirectory(p) && Files.exists(p.resolve("mmc-pack.json")) && Files.exists(p.resolve("instance.cfg"))) {
						dirs.add(p);
					}
				}
			} catch (IOException ignored) {
				// папку не прочитать — просто не показываем инстансы
			}

			dirs.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

			for (Path p : dirs) {
				Target t = prismTarget(p);

				if (t != null) {
					found.add(t);
				}
			}
		}

		Path minecraft = appData.resolve(".minecraft");

		if (Files.isDirectory(minecraft)) {
			found.add(vanillaTarget(minecraft, AnchorLang.tr("Официальный лаунчер (.minecraft)")));
		}

		return found;
	}

	/**
	 * Определяет, чем является выбранная папка: корнем инстанса Prism, его подпапкой minecraft (как её открывает кнопка Folder),
	 * папкой игры официального лаунчера или чем-то постороннним (тогда у цели заполнен {@code problem}).
	 */
	static Target classify(Path dir) {
		Path abs = dir.toAbsolutePath().normalize();

		if (isPrismInstance(abs)) {
			return prismTarget(abs);
		}

		String name = abs.getFileName() == null ? "" : abs.getFileName().toString();
		Path parent = abs.getParent();

		if (parent != null && (name.equals("minecraft") || name.equals(".minecraft")) && isPrismInstance(parent)) {
			return prismTarget(parent);
		}

		return vanillaTarget(abs, abs.toString());
	}

	private static boolean isPrismInstance(Path dir) {
		return Files.exists(dir.resolve("mmc-pack.json")) && Files.exists(dir.resolve("instance.cfg"));
	}

	private static Target prismTarget(Path instanceDir) {
		String pack;

		try {
			pack = new String(Files.readAllBytes(instanceDir.resolve("mmc-pack.json")), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}

		String name = instanceName(instanceDir);
		String version = AnchorMain.extractComponentVersion(pack, "net.minecraft");
		String label = "Prism: " + name + (version != null ? " (" + version + ")" : "");
		String problem = "";
		String warning = "";

		if (version == null) {
			problem = AnchorLang.tr("в этом инстансе не найдена версия Minecraft");
		} else if (!AnchorMain.isSupported(version)) {
			problem = AnchorLang.tr("Версия Minecraft ") + version + AnchorLang.tr(" пока не поддерживается (сейчас: ") + String.join(", ", AnchorMain.SUPPORTED_VERSIONS) + ")";
		}

		if (AnchorMain.hasComponent(pack, "net.fabricmc.fabric-loader")) {
			warning = AnchorLang.tr("В этом инстансе уже есть Fabric Loader: убери его в Edit Instance → Version, иначе игра не запустится.");
		}

		return new Target(label, instanceDir, true, version, problem, warning);
	}

	private static Target vanillaTarget(Path dir, String label) {
		boolean looksLikeGameFolder = Files.isDirectory(dir.resolve("versions")) || Files.exists(dir.resolve("launcher_profiles.json"))
				|| ".minecraft".equals(dir.getFileName() == null ? "" : dir.getFileName().toString());
		String problem = looksLikeGameFolder ? "" : AnchorLang.tr("Эта папка не похожа на папку игры (нет versions, launcher_profiles.json или инстанса Prism).");

		return new Target(label, dir, false, null, problem, "");
	}

	private static String instanceName(Path instanceDir) {
		try {
			for (String line : Files.readAllLines(instanceDir.resolve("instance.cfg"), StandardCharsets.UTF_8)) {
				if (line.startsWith("name=") && line.length() > 5) {
					return line.substring(5).trim();
				}
			}
		} catch (IOException ignored) {
			// имя не прочитать — возьмём имя папки
		}

		return instanceDir.getFileName().toString();
	}

	/** Папка инстансов, если игрок переназначил её в настройках Prism (InstanceDir в prismlauncher.cfg). */
	private static Path configuredInstanceDir(Path prismRoot) {
		Path cfg = prismRoot.resolve("prismlauncher.cfg");

		if (!Files.isRegularFile(cfg)) {
			return null;
		}

		try {
			for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
				if (line.startsWith("InstanceDir=") && line.length() > 12) {
					Path p = java.nio.file.Paths.get(line.substring(12).trim());

					return p.isAbsolute() ? p : prismRoot.resolve(p);
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// конфиг не прочитать — используем стандартную папку
		}

		return null;
	}

	/** Папка %APPDATA% (на других системах null — автопоиск не работает, остаётся ручной выбор). */
	static Path appData() {
		String appData = System.getenv("APPDATA");

		return appData == null || appData.isEmpty() || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? null : java.nio.file.Paths.get(appData);
	}

	static String describe(List<Target> targets) {
		StringBuilder sb = new StringBuilder();

		for (Target t : targets) {
			sb.append(t.label).append(" | ").append(t.dir).append(" | prism=").append(t.prism).append(" | problem=").append(t.problem.isEmpty() ? "-" : t.problem)
					.append(" | warning=").append(t.warning.isEmpty() ? "-" : t.warning).append('\n');
		}

		return sb.toString();
	}

	static List<String> supportedVersions() {
		return Arrays.asList(AnchorMain.SUPPORTED_VERSIONS);
	}
}
