package com.ganichgg.argentum.client.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.modrinth.ModrinthCache;

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
