/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.failsafe;

import net.fabricmc.loader.impl.i18n.Lang;
import net.fabricmc.loader.impl.modrinth.CrashDiagnoser;

/**
 * Объяснение падения простыми словами: что случилось, кто вероятно виноват и что нажать. Текст собирается в основном
 * процессе игры (там работает перевод), окно ошибки только показывает готовые строки.
 */
public final class CrashExplainer {
	public final String heading;
	public final String explanation;
	public final String action;
	/** Вероятный виновник или null. */
	public final CrashDiagnoser.Suspect suspect;

	private CrashExplainer(String heading, String explanation, String action, CrashDiagnoser.Suspect suspect) {
		this.heading = heading;
		this.explanation = explanation;
		this.action = action;
		this.suspect = suspect;
	}

	/**
	 * @param suspect    самый вероятный мод-виновник или null
	 * @param mcVersion  версия игры для подсказок («поставь версию мода для 1.21.1»)
	 */
	public static CrashExplainer explain(CrashAnalysis a, CrashDiagnoser.Suspect suspect, String mcVersion) {
		String mod = suspect != null ? "«" + suspect.name + "»" : null;
		String ver = mcVersion == null || mcVersion.isEmpty() ? "" : " " + mcVersion;

		switch (a.kind) {
		case OUT_OF_MEMORY:
			return new CrashExplainer(Lang.tr("Игре не хватило памяти"),
					Lang.tr("Модам, ресурспакам или шейдерам понадобилось больше оперативной памяти, чем игре выделено."),
					Lang.tr("Выдели игре больше памяти в настройках лаунчера (обычно хватает 4–6 ГБ) или убери самые тяжёлые моды и шейдеры."),
					null);
		case JAVA_VERSION:
			return new CrashExplainer(mod != null ? String.format(Lang.tr("Моду %s нужна более новая Java"), mod) : Lang.tr("Одному из модов нужна более новая Java"),
					Lang.tr("Мод собран под более новую версию Java, чем та, на которой запущена игра."),
					Lang.tr("Выбери в настройках лаунчера Java 21 или новее. Если не помогло — поставь версию мода для своей версии игры."),
					suspect);
		case MIXIN:
			return new CrashExplainer(mod != null ? String.format(Lang.tr("Мод %s не смог встроиться в игру"), mod) : Lang.tr("Один из модов не смог встроиться в игру"),
					Lang.tr("Мод пытался изменить код игры и не нашёл нужного места. Так бывает, когда мод сделан для другой версии игры или конфликтует с другим модом."),
					mod != null ? String.format(Lang.tr("Отключи %s и запусти игру снова. Если мод нужен — поставь его версию для Minecraft%s."), mod, ver)
							: Lang.tr("Сохрани пакет диагностики и отправь его — по нему видно, какой мод виноват."),
					suspect);
		case MISSING_CLASS:
			return new CrashExplainer(mod != null ? String.format(Lang.tr("Моду %s чего-то не хватает"), mod) : Lang.tr("Одному из модов чего-то не хватает"),
					Lang.tr("Мод обратился к части другого мода или игры, которой нет. Обычно не установлена нужная зависимость или мод сделан для другой версии."),
					mod != null ? String.format(Lang.tr("Поставь через каталог Argentum моды, которые нужны %s, или его версию для Minecraft%s. Либо отключи его."), mod, ver)
							: Lang.tr("Проверь, что у всех модов стоят их зависимости. Каталог Argentum ставит их сам."),
					suspect);
		case INCOMPATIBLE:
			return new CrashExplainer(mod != null ? String.format(Lang.tr("Мод %s не подходит к этой версии"), mod) : Lang.tr("Один из модов не подходит к этой версии"),
					Lang.tr("Мод собран под другую версию игры или другого мода: нужного ему кода здесь нет."),
					mod != null ? String.format(Lang.tr("Обнови %s до версии для Minecraft%s или отключи его."), mod, ver)
							: Lang.tr("Обнови моды через каталог Argentum — «Обновить всё»."),
					suspect);
		case CONFIG:
			return new CrashExplainer(mod != null ? String.format(Lang.tr("Повреждён файл настроек мода %s"), mod) : Lang.tr("Повреждён файл настроек одного из модов"),
					Lang.tr("Мод не смог прочитать свой файл настроек: он испорчен или записан неправильно."),
					Lang.tr("Удали файл настроек этого мода в папке config — мод создаст новый с настройками по умолчанию."),
					suspect);
		case GRAPHICS:
			return new CrashExplainer(Lang.tr("Проблема с видеокартой или драйвером"),
					Lang.tr("Игра не смогла работать с графикой. Часто виноват устаревший драйвер видеокарты или тяжёлый шейдер."),
					Lang.tr("Обнови драйвер видеокарты. Если стоят шейдеры — отключи их и запусти игру снова."),
					suspect);
		case MOD_ERROR:
			return new CrashExplainer(mod != null ? String.format(Lang.tr("Ошибка в моде %s"), mod) : Lang.tr("Ошибка в одном из модов"),
					Lang.tr("В коде мода произошла ошибка, и игра не смогла продолжить."),
					mod != null ? String.format(Lang.tr("Отключи %s и запусти игру снова. Если мод нужен — обнови его."), mod)
							: Lang.tr("Сохрани пакет диагностики и отправь его — по нему видно, какой мод виноват."),
					suspect);
		default:
			return new CrashExplainer(mod != null ? String.format(Lang.tr("Игра упала, вероятно из-за мода %s"), mod) : Lang.tr("Игра упала"),
					mod != null ? Lang.tr("Точную причину определить не удалось, но этот мод чаще всего встречается в отчёте об ошибке.")
							: Lang.tr("Точную причину определить не удалось."),
					mod != null ? String.format(Lang.tr("Попробуй отключить %s. Если не поможет — верни прошлый рабочий набор модов."), mod)
							: Lang.tr("Верни прошлый рабочий набор модов или сохрани пакет диагностики и отправь его."),
					suspect);
		}
	}

	/** Короткий текст для форума или сообщения автору: версии, вывод и первопричина. */
	public String summary(CrashAnalysis a, String argentumVersion, String mcVersion) {
		StringBuilder sb = new StringBuilder();
		sb.append("Argentum ").append(argentumVersion).append(", Minecraft ").append(mcVersion).append('\n');
		sb.append(heading).append('\n');

		if (suspect != null) {
			sb.append(Lang.tr("Вероятная причина: ")).append(suspect.name).append(" (").append(suspect.fileName).append(")\n");
		}

		if (a.root != null) {
			String root = a.root.toString();
			sb.append(Lang.tr("Ошибка: ")).append(root.length() > 300 ? root.substring(0, 297) + "..." : root).append('\n');

			for (int i = 0; i < Math.min(3, a.root.frames.size()); i++) {
				sb.append("  at ").append(a.root.frames.get(i)).append('\n');
			}
		}

		if (a.firstLogError != null) {
			sb.append(Lang.tr("Первая ошибка в журнале: ")).append(a.firstLogError).append('\n');
		}

		return sb.toString();
	}
}
