/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.installer.anchor;

import java.util.Locale;
import java.util.Map;

/**
 * Перевод текстов установщика («текст как ключ», как Lang в загрузчике). Язык берётся из -Dargentum.lang или из языка
 * системы: русский оставляет оригиналы, остальные получают английский. Таблица лежит прямо в коде, чтобы не зависеть от
 * ресурсов в native-image.
 */
final class AnchorLang {
	private static final Map<String, String> EN = Map.ofEntries(
			Map.entry("<html><b>ArgentumLoader установлен!</b><br><br>",
					"<html><b>ArgentumLoader is installed!</b><br><br>"),
			Map.entry("Компонент записан в:<br>",
					"The component was written to:<br>"),
			Map.entry("Открой Prism Launcher, зайди в <b>Edit Instance → Version</b> — там уже должен быть<br>",
					"Open Prism Launcher and go to <b>Edit Instance → Version</b> — you should already see<br>"),
			Map.entry("ArgentumLoader. Недостающие библиотеки Prism скачает сам при запуске.</html>",
					"ArgentumLoader there. Prism downloads the missing libraries itself on first launch.</html>"),
			Map.entry("Профиль записан в:<br>",
					"The profile was written to:<br>"),
			Map.entry("Теперь выберите профиль <b>ArgentumLoader</b> в лаунчере Minecraft и нажмите «Играть» —<br>",
					"Now choose the <b>ArgentumLoader</b> profile in the Minecraft launcher and press Play —<br>"),
			Map.entry("недостающие библиотеки лаунчер скачает сам.</html>",
					"the launcher downloads the missing libraries itself.</html>"),
			Map.entry("<html><b>Не удалось установить ArgentumLoader</b><br><br>",
					"<html><b>Could not install ArgentumLoader</b><br><br>"),
			Map.entry("нет окна для выбора версии — укажите версию Minecraft первым аргументом командной строки",
					"no window to choose a version — pass the Minecraft version as the first command-line argument"),
			Map.entry("установка отменена",
					"installation cancelled"),
			Map.entry("версия ",
					"version "),
			Map.entry(" пока не поддерживается ArgentumLoader (сейчас доступна: ",
					" is not supported by ArgentumLoader yet (available now: "),
			Map.entry("не удалось найти версию Minecraft (компонент net.minecraft) в ",
					"could not find the Minecraft version (net.minecraft component) in "),
			Map.entry(" (из этого инстанса Prism) пока не поддерживается ArgentumLoader ",
					" (from this Prism instance) is not supported by ArgentumLoader yet "),
			Map.entry("(сейчас доступна: ",
					"(available now: "),
			Map.entry("<html>Папка установки:<br><b>",
					"<html>Install folder:<br><b>"),
			Map.entry("Версия Minecraft:</html>",
					"Minecraft version:</html>"),
			Map.entry("ArgentumLoader — установка",
					"ArgentumLoader — installation"),
			Map.entry("не найден ",
					"not found: "),
			Map.entry(" внутри якоря — сборка повреждена",
					" inside the anchor — the build is corrupted"),
			Map.entry("отсутствует обязательный ключ '",
					"required key '"),
			Map.entry("' в ",
					"' is missing in "),
			Map.entry("в mmc-pack.json не найден ключ \"components\"",
					"the \"components\" key was not found in mmc-pack.json"),
			Map.entry("не удалось найти конец массива \"components\" в mmc-pack.json",
					"could not find the end of the \"components\" array in mmc-pack.json"),
			Map.entry("SHA-1 недоступен в этой JVM",
					"SHA-1 is unavailable in this JVM"));

	private static final boolean RUSSIAN = isRussian();

	private AnchorLang() { }

	private static boolean isRussian() {
		String forced = System.getProperty("argentum.lang");
		String code = forced != null && !forced.isEmpty() ? forced : Locale.getDefault().getLanguage();

		return code.toLowerCase(Locale.ROOT).startsWith("ru");
	}

	static String tr(String original) {
		return RUSSIAN ? original : EN.getOrDefault(original, original);
	}
}
