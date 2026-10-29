/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * Точки подключения Argentum Core к платформе, на которой он работает (Argentum Legacy на Fabric Loader, Argentum 3.0 на
 * NeoForge): папка игры и версия. Платформа заполняет их как можно раньше при запуске; до этого действуют безопасные
 * значения по умолчанию (текущая папка, версия «dev»).
 */
public final class CorePlatform {
	private static volatile Supplier<Path> gameDir = () -> Paths.get(".");
	private static volatile String version = "dev";
	private static volatile String name = "Argentum";

	private CorePlatform() { }

	/**
	 * @param gameDirSupplier папка игры (может быть известна не сразу — поэтому поставщик, а не значение)
	 * @param platformVersion версия Argentum для заголовков запросов и диагностики
	 * @param platformName    название редакции: «ArgentumLoader» (Legacy) или «Argentum» (3.0)
	 */
	public static void install(Supplier<Path> gameDirSupplier, String platformVersion, String platformName) {
		gameDir = gameDirSupplier;
		version = platformVersion;
		name = platformName;
	}

	/** Папка игры; если платформа её ещё не знает — текущая папка. */
	public static Path gameDir() {
		try {
			Path p = gameDir.get();

			return p != null ? p : Paths.get(".");
		} catch (RuntimeException e) {
			return Paths.get(".");
		}
	}

	public static String version() {
		return version;
	}

	public static String name() {
		return name;
	}
}
