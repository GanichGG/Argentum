/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.hotload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.fabricmc.loader.impl.i18n.Lang;

/** Итог попытки загрузить моды на лету: по каждому моду — что получилось и почему, по-русски. */
public final class HotLoadReport {
	public enum Status {
		/** Мод загружен и работает прямо сейчас. */
		LOADED,
		/** Мод нельзя безопасно загрузить в запущенную игру — заработает после перезапуска. */
		NEEDS_RESTART,
		/** Мод уже был загружен ранее. */
		ALREADY_LOADED,
		/** Не загрузился из-за ошибки. */
		FAILED
	}

	public static final class Entry {
		public final String modId;
		public final String name;
		public final Status status;
		public final List<String> notes;
		/** У загруженного мода есть assets/ — нужна перезагрузка ресурсов, чтобы он их увидел. */
		public final boolean hasAssets;

		Entry(String modId, String name, Status status, List<String> notes, boolean hasAssets) {
			this.modId = modId;
			this.name = name;
			this.status = status;
			this.notes = Collections.unmodifiableList(notes);
			this.hasAssets = hasAssets;
		}
	}

	private final List<Entry> entries = new ArrayList<>();

	void add(String modId, String name, Status status, List<String> notes, boolean hasAssets) {
		this.entries.add(new Entry(modId, name, status, new ArrayList<>(notes), hasAssets));
	}

	public List<Entry> entries() {
		return Collections.unmodifiableList(this.entries);
	}

	public boolean anyLoaded() {
		return count(Status.LOADED) > 0;
	}

	public boolean needsResourceReload() {
		for (Entry e : this.entries) {
			if (e.status == Status.LOADED && e.hasAssets) {
				return true;
			}
		}

		return false;
	}

	public int count(Status status) {
		int n = 0;

		for (Entry e : this.entries) {
			if (e.status == status) {
				n++;
			}
		}

		return n;
	}

	/** Одна строка для статус-бара. */
	public String summary() {
		int loaded = count(Status.LOADED);
		int restart = count(Status.NEEDS_RESTART);
		int failed = count(Status.FAILED);
		List<String> parts = new ArrayList<>();

		if (loaded > 0) {
			parts.add(Lang.tr("работает сразу: ") + names(Status.LOADED));
		}

		if (restart > 0) {
			Entry first = firstOf(Status.NEEDS_RESTART);
			String why = first.notes.isEmpty() ? "" : " (" + first.notes.get(0) + ")";
			parts.add(Lang.tr("после перезапуска: ") + names(Status.NEEDS_RESTART) + why);
		}

		if (failed > 0) {
			Entry first = firstOf(Status.FAILED);
			parts.add(Lang.tr("ошибка: ") + names(Status.FAILED) + (first.notes.isEmpty() ? "" : " — " + first.notes.get(0)));
		}

		return parts.isEmpty() ? Lang.tr("Нечего загружать") : String.join("; ", parts);
	}

	private Entry firstOf(Status status) {
		for (Entry e : this.entries) {
			if (e.status == status) {
				return e;
			}
		}

		throw new IllegalStateException();
	}

	private String names(Status status) {
		List<String> names = new ArrayList<>();

		for (Entry e : this.entries) {
			if (e.status == status) {
				names.add(e.name);
			}
		}

		return String.join(", ", names);
	}
}
