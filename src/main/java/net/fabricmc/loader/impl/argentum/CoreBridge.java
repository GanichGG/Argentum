/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.argentum;

import com.ganichgg.argentum.core.CorePlatform;

import net.fabricmc.loader.impl.ArgentumVersion;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogLevel;

/**
 * Подключение Argentum Core к Argentum Legacy (Fabric Loader): папка игры, версия и журнал. Вызывается как можно раньше
 * при запуске; повторные вызовы ничего не меняют.
 */
public final class CoreBridge {
	private static volatile boolean installed;

	private CoreBridge() { }

	public static void install() {
		if (installed) {
			return;
		}

		installed = true;
		CorePlatform.install(() -> FabricLoaderImpl.INSTANCE != null ? FabricLoaderImpl.INSTANCE.getGameDir() : null,
				ArgentumVersion.DISPLAY, "ArgentumLoader");
		com.ganichgg.argentum.core.log.Log.setSink((level, category, message, exc) -> {
			LogCategory cat = category == com.ganichgg.argentum.core.log.LogCategory.MODRINTH ? LogCategory.MODRINTH : LogCategory.GENERAL;
			Log.log(level(level), cat, message, exc);
		});
	}

	private static LogLevel level(com.ganichgg.argentum.core.log.Log.Level level) {
		switch (level) {
		case DEBUG:
			return LogLevel.DEBUG;
		case WARN:
			return LogLevel.WARN;
		case ERROR:
			return LogLevel.ERROR;
		default:
			return LogLevel.INFO;
		}
	}
}
