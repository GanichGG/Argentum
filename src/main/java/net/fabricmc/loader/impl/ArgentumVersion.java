/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Версия самого ArgentumLoader (не путать с {@link FabricLoaderImpl#VERSION} — версией Fabric Loader, на которой он основан).
 *
 * <p>Формат для людей: {@code x.y[буква номер]} - {@code x} глобальная версия, {@code y} подверсия, буква - стадия
 * ({@code A} альфа, {@code B} бета; у стабильной версии буквы нет), далее номер сборки: {@code 0.1A3}, {@code 0.1B1}, {@code 0.1}.
 * Загрузчику и модам нужна SemVer-запись той же версии (иначе не работают диапазоны в {@code depends}): {@code 0.1B1} ->
 * {@code 0.1.0-b.1}; она сортируется правильно: {@code 0.1.0-a.3 < 0.1.0-b.1 < 0.1.0}.
 *
 * <p>Это единственное место, где записана версия: сборка (gradle) читает константу отсюда же.
 */
public final class ArgentumVersion {
	/** Версия для людей: имена файлов, теги, Prism, заголовки журнала изменений. */
	public static final String DISPLAY = "0.1B3";

	private static final Pattern FORMAT = Pattern.compile("(\\d+)\\.(\\d+)(?:([AB])(\\d+))?");

	private ArgentumVersion() { }

	/** SemVer-запись {@link #DISPLAY}: её видят загрузчик и моды. */
	public static String semver() {
		return toSemver(DISPLAY);
	}

	/** @throws IllegalArgumentException если запись не в формате {@code x.y[A|B номер]} */
	public static String toSemver(String display) {
		Matcher m = FORMAT.matcher(display);

		if (!m.matches()) {
			throw new IllegalArgumentException("Not an ArgentumLoader version (expected x.y, x.yAn or x.yBn): " + display);
		}

		String base = m.group(1) + "." + m.group(2) + ".0";

		return m.group(3) == null ? base : base + "-" + m.group(3).toLowerCase(java.util.Locale.ROOT) + "." + m.group(4);
	}

	/** Это альфа или бета (в релизе GitHub такая версия помечается как пре-релиз). */
	public static boolean isPreRelease(String display) {
		return FORMAT.matcher(display).matches() && display.matches(".*[AB]\\d+");
	}
}
