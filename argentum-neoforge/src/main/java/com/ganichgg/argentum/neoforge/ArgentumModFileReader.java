/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.neoforge;

import cpw.mods.jarhandling.JarContents;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Читатель модов с самым высоким приоритетом: NeoForge показывает ему каждый найденный jar раньше своих читателей.
 * Сейчас только запоминает jar и его вид и отдаёт дальше (возвращает null); позже здесь Fabric- и Forge-моды будут
 * подменяться переведёнными версиями.
 */
public final class ArgentumModFileReader implements IModFileReader {
	public ArgentumModFileReader() {
		HybridDiscovery.start();
	}

	@Override
	public int getPriority() {
		return Integer.MAX_VALUE;
	}

	@Override
	public IModFile read(JarContents jar, ModFileDiscoveryAttributes attributes) {
		try {
			HybridDiscovery.saw(jar.getPrimaryPath());
		} catch (Throwable t) {
			HybridDiscovery.LOGGER.warn("Argentum 3.0: не удалось осмотреть {}", jar.getPrimaryPath(), t);
		}

		return null; // решает NeoForge, как и без Argentum
	}

	@Override
	public String toString() {
		return "Argentum hybrid reader";
	}
}
