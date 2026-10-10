/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
