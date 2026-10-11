/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl.discovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.LoaderUtil;

/**
 * ArgentumLoader hot-loader: поиск модов в указанных jar-файлах уже после старта игры.
 * Живёт в этом пакете, потому что {@link ModCandidateFinder} виден только здесь.
 */
public final class HotModDiscovery {
	private HotModDiscovery() { }

	/**
	 * Читает метаданные модов из jar (вместе с вложенными jar-in-jar). Возвращает только то, что ещё не загружено
	 * (встроенные «моды» вроде java и minecraft пропускаются). Серверные моды в результат не попадают.
	 */
	public static List<ModCandidateImpl> discover(List<Path> jars) throws ModResolutionException {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		ModDiscoverer discoverer = new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(loader.getConfigDir()));

		discoverer.addCandidateFinder(out -> {
			for (Path jar : jars) {
				out.accept(LoaderUtil.normalizeExistingPath(jar), false);
			}
		});

		Map<String, Set<ModCandidateImpl>> envDisabled = new HashMap<>();
		List<ModCandidateImpl> result = new ArrayList<>();

		for (ModCandidateImpl candidate : discoverer.discoverMods(loader, envDisabled)) {
			if (!candidate.isBuiltin() && !loader.isModLoaded(candidate.getId())) {
				result.add(candidate);
			}
		}

		return result;
	}
}
