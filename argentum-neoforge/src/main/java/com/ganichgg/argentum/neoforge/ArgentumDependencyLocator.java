/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.neoforge;

import java.util.List;

import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * Вызывается NeoForge после поиска модов: момент подвести итог (отчёт о видах модов). Позже здесь будут добавляться
 * переведённые Fabric- и Forge-моды и проверяться общий граф зависимостей.
 */
public final class ArgentumDependencyLocator implements IDependencyLocator {
	public ArgentumDependencyLocator() {
		HybridDiscovery.start();
	}

	@Override
	public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
		try {
			HybridDiscovery.finish();
		} catch (Throwable t) {
			HybridDiscovery.LOGGER.warn("Argentum 3.0: итог поиска модов не подведён", t);
		}
	}

	@Override
	public String toString() {
		return "Argentum hybrid locator";
	}
}
