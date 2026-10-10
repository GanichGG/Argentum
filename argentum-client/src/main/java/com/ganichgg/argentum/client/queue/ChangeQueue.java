package com.ganichgg.argentum.client.queue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.impl.modrinth.ModrinthProject;
import net.fabricmc.loader.impl.modrinth.ModrinthVersion;

/**
 * Очередь изменений модов, которую игрок набирает в каталоге: ничего не скачивается и не удаляется, пока он не нажмёт
 * «Применить изменения». На один мод — одно изменение (последнее). Очередь общая для сеанса игры, поэтому переживает
 * закрытие и повторное открытие каталога.
 */
public final class ChangeQueue {
	public enum Kind { INSTALL, VERSION, REMOVE, DISABLE, ENABLE }

	public static final class Change {
		public final Kind kind;
		public final ModrinthProject project;
		/** Конкретная версия (обновление, откат, выбор в списке) или null — подобрать последнюю подходящую. */
		public final ModrinthVersion version;

		public Change(Kind kind, ModrinthProject project, ModrinthVersion version) {
			this.kind = kind;
			this.project = project;
			this.version = version;
		}
	}

	private static final Map<String, Change> CHANGES = new LinkedHashMap<>();

	private static volatile boolean applying;
	private static volatile boolean preparing;
	private static volatile boolean dirty;
	private static volatile float work;

	private ChangeQueue() { }

	// ---------------------------------------------------------------- набор изменений

	public static synchronized Change get(String projectId) {
		return CHANGES.get(projectId);
	}

	public static synchronized void put(Change change) {
		CHANGES.put(change.project.id, change);
	}

	public static synchronized void remove(String projectId) {
		CHANGES.remove(projectId);
	}

	public static synchronized List<Change> snapshot() {
		return new ArrayList<>(CHANGES.values());
	}

	public static synchronized int size() {
		return CHANGES.size();
	}

	/** Сколько «пунктов» ждёт применения: изменения из очереди плюс файлы, уже изменённые на диске (импорт сборки, отключение после падения). */
	public static int count() {
		return size() + (dirty ? 1 : 0);
	}

	// ---------------------------------------------------------------- ход применения

	/** Файлы на диске уже изменены (мимо очереди), нужен только перезапуск. */
	public static void markDirty() {
		dirty = true;
	}

	public static boolean dirty() {
		return dirty;
	}

	public static boolean applying() {
		return applying;
	}

	/** Применение началось: сначала меняем файлы (work 0..1), потом готовим и переключаем клиент. */
	public static void begin() {
		work = 0F;
		preparing = false;
		applying = true;
	}

	/** Применять нечего, нужно лишь подготовить копию игры заново (повтор после неудачи). */
	public static void beginPrepareOnly() {
		work = 1F;
		preparing = true;
		applying = true;
	}

	public static void setWork(float value) {
		work = Math.max(0F, Math.min(1F, value));
	}

	public static float work() {
		return work;
	}

	/** Файлы готовы, дальше ход определяет подготовка копии игры. */
	public static void filesDone() {
		work = 1F;
		dirty = false;
		preparing = true;
	}

	public static boolean preparing() {
		return preparing;
	}

	public static void end() {
		applying = false;
		preparing = false;
	}
}
