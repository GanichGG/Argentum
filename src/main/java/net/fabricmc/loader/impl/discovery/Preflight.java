/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.discovery;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ganichgg.argentum.core.i18n.Lang;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.LoaderUtil;

/**
 * Предпроверка набора модов до запуска игры: те же поиск и разрешение зависимостей, что при настоящем старте, но на
 * заданном списке jar, без загрузки чего-либо в игру. Ошибки приходят в том же читаемом виде, что и экран ошибки лоадера.
 * Живёт в этом пакете, потому что {@link ModCandidateFinder} виден только здесь.
 */
public final class Preflight {
	private Preflight() { }

	/**
	 * @param jars итоговый набор: все файлы модов, которые будут лежать в mods после применения изменений
	 * @return null, если набор разрешается; иначе ошибка с описанием причины
	 */
	public static ModResolutionException check(List<Path> jars) {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;

		try {
			ModDiscoverer discoverer = new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(loader.getConfigDir()));

			discoverer.addCandidateFinder(new ClasspathModCandidateFinder()); // сам лоадер (fabricloader) приходит как мод с classpath
			discoverer.addCandidateFinder(out -> {
				for (Path jar : jars) {
					out.accept(LoaderUtil.normalizeExistingPath(jar), false);
				}
			});

			Map<String, Set<ModCandidateImpl>> envDisabled = new HashMap<>();
			ModResolver.resolve(discoverer.discoverMods(loader, envDisabled), loader.getEnvironmentType(), envDisabled);

			return null;
		} catch (ModResolutionException e) {
			return e;
		} catch (RuntimeException e) {
			return new ModResolutionException(Lang.tr("Не удалось проверить набор модов: ") + e.getMessage(), e);
		}
	}
}
