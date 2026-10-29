/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.client.ui;

import com.ganichgg.argentum.core.modrinth.ModrinthCache;

import net.fabricmc.loader.api.FabricLoader;

/** Один общий дисковый кэш Modrinth на игру: {@code <папка игры>/.argentum/cache}. */
public final class ClientCache {
	private static ModrinthCache instance;

	private ClientCache() { }

	public static synchronized ModrinthCache get() {
		if (instance == null) {
			instance = ModrinthCache.open(FabricLoader.getInstance().getGameDir().resolve(".argentum").resolve("cache"));
		}

		return instance;
	}
}
