/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl.failsafe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.hotload.PendingRemovals;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Безопасный запуск модов (клиент): если у мода падает точка входа, игра не закрывается, а мод помечается отключённым
 * (его файл отключится при следующем запуске), моды, которые от него зависят, пропускаются, и всё это запоминается, чтобы
 * клиентская часть явно показала игроку, что отключено и почему.
 *
 * <p>Не перехватывается то, что нельзя обезвредить: ошибки JVM (нехватка памяти), моды вне папки mods (встроенные,
 * вложенные jar-in-jar, classpath) и любой сервер — там поведение прежнее, игра останавливается с ошибкой. Ошибки
 * миксинов при загрузке классов сюда не попадают вовсе.
 *
 * <p>Выключается свойством {@code -Dargentum.failsafe=false}.
 */
public final class FailSafe {
	/** Один отключённый мод: кто, на каком этапе и по какой причине. */
	public static final class Failure {
		public final String modId;
		public final String name;
		public final String version;
		/** Этап запуска: preLaunch, main, client. */
		public final String stage;
		/** Короткая причина для игрока. */
		public final String reason;
		/** Имя jar в папке mods; null, если файл определить не удалось. */
		public final String fileName;
		/** true — мод не запускался вообще, потому что зависит от упавшего. */
		public final boolean dependent;

		Failure(String modId, String name, String version, String stage, String reason, String fileName, boolean dependent) {
			this.modId = modId;
			this.name = name;
			this.version = version;
			this.stage = stage;
			this.reason = reason;
			this.fileName = fileName;
			this.dependent = dependent;
		}
	}

	private static final List<Failure> FAILURES = new CopyOnWriteArrayList<>();
	private static final Set<String> FAILED_IDS = Collections.synchronizedSet(new HashSet<>());

	private FailSafe() { }

	public static boolean enabled() {
		return !"false".equalsIgnoreCase(System.getProperty("argentum.failsafe"))
				&& FabricLoaderImpl.INSTANCE.getEnvironmentType() == EnvType.CLIENT;
	}

	/** Всё, что было отключено за этот запуск (в порядке возникновения). */
	public static List<Failure> failures() {
		return Collections.unmodifiableList(new ArrayList<>(FAILURES));
	}

	/** Имя файла мода (как в папке mods), если он отключён из-за ошибки запуска; иначе null. */
	public static Failure failureForFile(String fileName) {
		for (Failure f : FAILURES) {
			if (fileName.equals(f.fileName)) {
				return f;
			}
		}

		return null;
	}

	/** Точку входа вызывать нельзя: мод уже упал на прошлом этапе либо зависит от упавшего. Зависимых запоминает как отключённых. */
	public static boolean skipDependent(String stage, ModContainer mod) {
		if (!enabled() || FAILED_IDS.isEmpty()) {
			return false;
		}

		if (FAILED_IDS.contains(mod.getMetadata().getId())) {
			return true;
		}

		for (ModDependency dep : mod.getMetadata().getDependencies()) {
			if (dep.getKind() == ModDependency.Kind.DEPENDS && FAILED_IDS.contains(dep.getModId())) {
				String file = ownFile(mod);

				if (file == null) {
					return false; // вложенный или встроенный мод — оставляем как было
				}

				record(mod, stage, "Не запускался: зависит от «" + dep.getModId() + "», который не запустился", file, true);

				return true;
			}
		}

		return false;
	}

	/**
	 * @return true, если ошибку проглотили (мод отключён, игра продолжает запуск); false — ошибку надо пробросить как раньше
	 */
	public static boolean handle(String stage, ModContainer mod, Throwable t) {
		if (!enabled() || t instanceof VirtualMachineError || t instanceof ThreadDeath || t instanceof InterruptedException) {
			return false;
		}

		String file = ownFile(mod);

		if (file == null) {
			return false;
		}

		Log.error(LogCategory.ENTRYPOINT, "Мод «%s» не запустился на этапе '%s' и будет отключён: %s", mod.getMetadata().getId(), stage, t);
		record(mod, stage, reasonOf(t), file, false);

		return true;
	}

	private static void record(ModContainer mod, String stage, String reason, String file, boolean dependent) {
		String id = mod.getMetadata().getId();

		if (!FAILED_IDS.add(id)) {
			return;
		}

		FAILURES.add(new Failure(id, mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(), stage, reason, file, dependent));

		try {
			PendingRemovals.scheduleDisable(FabricLoaderImpl.INSTANCE.getGameDir().resolve("mods"), file); // со следующего запуска мод не грузится
		} catch (IOException e) {
			Log.warn(LogCategory.ENTRYPOINT, "Не удалось пометить мод %s отключённым: %s", id, e);
		}
	}

	/** Имя jar, если мод лежит прямо в папке mods (только такие можно отключить); иначе null. */
	private static String ownFile(ModContainer mod) {
		ModOrigin origin = mod.getOrigin();

		if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) {
			return null;
		}

		Path path = origin.getPaths().get(0).toAbsolutePath().normalize();
		Path mods = FabricLoaderImpl.INSTANCE.getGameDir().resolve("mods").toAbsolutePath().normalize();

		if (!mods.equals(path.getParent()) || !path.getFileName().toString().endsWith(".jar")) {
			return null;
		}

		return path.getFileName().toString();
	}

	private static String reasonOf(Throwable t) {
		Throwable root = t;

		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}

		String m = root.getMessage();
		String what = root.getClass().getSimpleName();

		if (m == null || m.isEmpty()) {
			return what;
		}

		String text = what + ": " + m;

		return text.length() > 160 ? text.substring(0, 157) + "..." : text;
	}
}
