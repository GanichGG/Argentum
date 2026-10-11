/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl.hotload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.HotModDiscovery;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.launch.FabricMixinBootstrap;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Hot-loader, этап 1: подключает свежеустановленные моды к уже запущенной игре, если это безопасно.
 *
 * <p>Что делается: читаем метаданные, проверяем зависимости и {@link HotLoadAnalyzer}, добавляем jar в classpath,
 * регистрируем мод в лоадере, подключаем его миксины (для ещё не загруженных классов) и запускаем его
 * {@code main}/{@code client}-инициализаторы. Всё, что небезопасно, честно остаётся «после перезапуска» с причиной.
 *
 * <p>Вызывать из потока рендера (инициализаторы модов ожидают именно его).
 */
public final class HotLoader {
	private HotLoader() { }

	public static synchronized HotLoadReport load(List<Path> jars) {
		HotLoadReport report = new HotLoadReport();
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		EnvType env = loader.getEnvironmentType();
		List<ModCandidateImpl> candidates;

		try {
			candidates = HotModDiscovery.discover(jars);
		} catch (Exception e) {
			Log.warn(LogCategory.GENERAL, "Hot-loader: не удалось прочитать моды", e);
			report.add("?", "Моды", HotLoadReport.Status.FAILED, Collections.singletonList("не удалось прочитать метаданные: " + e.getMessage()), false);

			return report;
		}

		// вложенные моды лежат внутри jar — достаём их на диск, чтобы анализировать и подключать как обычные
		for (ModCandidateImpl c : candidates) {
			try {
				if (!c.hasPath()) {
					c.setPaths(Collections.singletonList(c.copyToDir(loader.getGameDir().resolve(FabricLoaderImpl.CACHE_DIR_NAME).resolve("processedMods"), false)));
				}
			} catch (Exception e) {
				Log.warn(LogCategory.GENERAL, "Hot-loader: не удалось распаковать " + c.getId(), e);
			}
		}

		Map<ModCandidateImpl, List<String>> blockers = new IdentityHashMap<>();
		Map<String, ModCandidateImpl> byId = new HashMap<>();

		for (ModCandidateImpl c : candidates) {
			byId.put(c.getId(), c);

			for (String provided : c.getProvides()) {
				byId.put(provided, c);
			}

			blockers.put(c, c.hasPath() ? new ArrayList<>(HotLoadAnalyzer.blockers(c, env)) : new ArrayList<>(Collections.singletonList("не удалось распаковать")));
		}

		// зависимости и вложенность: могут тянуть блокировку по цепочке, поэтому считаем до устойчивого состояния
		boolean changed = true;

		while (changed) {
			changed = false;

			for (ModCandidateImpl c : candidates) {
				List<String> mine = blockers.get(c);

				if (!mine.isEmpty()) {
					continue;
				}

				String problem = dependencyProblem(c, byId, blockers, loader);

				if (problem == null && !c.getParentMods().isEmpty() && allParentsBlocked(c, blockers)) {
					problem = "входит в мод, который заработает только после перезапуска";
				}

				if (problem != null) {
					mine.add(problem);
					changed = true;
				}
			}
		}

		List<ModCandidateImpl> ready = new ArrayList<>();

		for (ModCandidateImpl c : candidates) {
			if (blockers.get(c).isEmpty()) {
				ready.add(c);
			} else {
				report.add(c.getId(), displayName(c), HotLoadReport.Status.NEEDS_RESTART, blockers.get(c), false);
			}
		}

		List<ModCandidateImpl> ordered = order(ready, byId);

		if (ordered.isEmpty()) {
			return report;
		}

		List<ModContainerImpl> containers;

		try {
			containers = loader.hotAddMods(ordered);
		} catch (Exception e) {
			Log.warn(LogCategory.GENERAL, "Hot-loader: регистрация модов не удалась", e);

			for (ModCandidateImpl c : ordered) {
				report.add(c.getId(), displayName(c), HotLoadReport.Status.FAILED, Collections.singletonList(describe(e)), false);
			}

			return report;
		}

		Set<String> failed = new HashSet<>();
		Map<String, String> failReason = new HashMap<>();

		try {
			FabricMixinBootstrap.addLate(env, containers);
		} catch (Throwable t) {
			Log.warn(LogCategory.MIXIN, "Hot-loader: миксины новых модов не подключились", t);

			for (ModContainerImpl mod : containers) {
				failed.add(mod.getMetadata().getId());
				failReason.put(mod.getMetadata().getId(), "миксины не подключились: " + describe(t));
			}
		}

		Set<String> ids = new HashSet<>();

		for (ModContainerImpl mod : containers) {
			ids.add(mod.getMetadata().getId());
		}

		runInitializers("main", ModInitializer.class, ids, failed, failReason, ModInitializer::onInitialize);

		if (env == EnvType.CLIENT) {
			runInitializers("client", ClientModInitializer.class, ids, failed, failReason, ClientModInitializer::onInitializeClient);
		}

		for (ModCandidateImpl c : ordered) {
			boolean assets = HotLoadAnalyzer.hasAssets(c);

			if (failed.contains(c.getId())) {
				report.add(c.getId(), displayName(c), HotLoadReport.Status.FAILED, Collections.singletonList(failReason.get(c.getId())), assets);
			} else {
				report.add(c.getId(), displayName(c), HotLoadReport.Status.LOADED, Collections.emptyList(), assets);
				Log.info(LogCategory.GENERAL, "Hot-loader: мод %s %s загружен без перезапуска", c.getId(), c.getVersion().getFriendlyString());
			}
		}

		return report;
	}

	private static <T> void runInitializers(String key, Class<T> type, Set<String> newIds, Set<String> failed, Map<String, String> failReason,
			java.util.function.Consumer<T> invoker) {
		for (EntrypointContainer<T> container : FabricLoaderImpl.INSTANCE.getEntrypointContainers(key, type)) {
			String id = container.getProvider().getMetadata().getId();

			if (!newIds.contains(id) || failed.contains(id)) {
				continue;
			}

			try {
				invoker.accept(container.getEntrypoint());
			} catch (Throwable t) {
				Log.warn(LogCategory.ENTRYPOINT, "Hot-loader: инициализатор '" + key + "' мода " + id + " упал", t);
				failed.add(id);
				failReason.put(id, "ошибка при запуске: " + describe(t));
			}
		}
	}

	private static String describe(Throwable t) {
		Throwable root = t;

		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}

		String message = root.getMessage();

		return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	private static String displayName(ModCandidateImpl c) {
		String name = c.getMetadata().getName();

		return name == null || name.isEmpty() ? c.getId() : name;
	}

	private static boolean allParentsBlocked(ModCandidateImpl c, Map<ModCandidateImpl, List<String>> blockers) {
		for (ModCandidateImpl parent : c.getParentMods()) {
			List<String> b = blockers.get(parent);

			if (b == null || b.isEmpty()) {
				return false; // родитель будет загружен (или уже загружен ранее) — вложенному можно за ним
			}
		}

		return true;
	}

	/** Первая найденная проблема с зависимостями мода; null — всё в порядке. */
	private static String dependencyProblem(ModCandidateImpl c, Map<String, ModCandidateImpl> batch,
			Map<ModCandidateImpl, List<String>> blockers, FabricLoaderImpl loader) {
		for (ModDependency dep : c.getDependencies()) {
			String id = dep.getModId();
			Version loadedVersion = loader.getModContainer(id).map(m -> m.getMetadata().getVersion()).orElse(null);
			ModCandidateImpl inBatch = batch.get(id);

			switch (dep.getKind()) {
			case DEPENDS:
				if (loadedVersion != null) {
					if (!dep.matches(loadedVersion)) {
						return "не подходит версия мода " + id + ": установлена " + loadedVersion.getFriendlyString();
					}
				} else if (inBatch == null) {
					return "требует мод " + id + ", которого нет в игре";
				} else if (!blockers.get(inBatch).isEmpty()) {
					return "зависит от мода " + id + ", который заработает только после перезапуска";
				} else if (!dep.matches(inBatch.getVersion())) {
					return "не подходит версия мода " + id + ": " + inBatch.getVersion().getFriendlyString();
				}

				break;
			case BREAKS:
				if (loadedVersion != null && dep.matches(loadedVersion)) {
					return "несовместим с загруженным модом " + id;
				}

				break;
			default:
				break;
			}
		}

		return null;
	}

	/** Зависимости раньше зависящих; порядок остальных сохраняется. */
	private static List<ModCandidateImpl> order(List<ModCandidateImpl> ready, Map<String, ModCandidateImpl> byId) {
		List<ModCandidateImpl> result = new ArrayList<>();
		Set<ModCandidateImpl> placed = Collections.newSetFromMap(new IdentityHashMap<>());
		List<ModCandidateImpl> pending = new ArrayList<>(ready);

		while (!pending.isEmpty()) {
			boolean progress = false;

			for (int i = 0; i < pending.size(); i++) {
				ModCandidateImpl c = pending.get(i);
				boolean waiting = false;

				for (ModDependency dep : c.getDependencies()) {
					if (dep.getKind() != ModDependency.Kind.DEPENDS) {
						continue;
					}

					ModCandidateImpl d = byId.get(dep.getModId());

					if (d != null && d != c && pending.contains(d) && !placed.contains(d)) {
						waiting = true;
						break;
					}
				}

				if (!waiting) {
					result.add(c);
					placed.add(c);
					pending.remove(i--);
					progress = true;
				}
			}

			if (!progress) { // цикл зависимостей — берём как есть
				result.addAll(pending);
				break;
			}
		}

		return result;
	}
}
