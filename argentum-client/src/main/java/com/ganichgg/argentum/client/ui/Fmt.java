package com.ganichgg.argentum.client.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.loader.impl.modrinth.ModrinthProject;

/** Форматирование чисел, времени и тегов для карточек. */
public final class Fmt {
	private static final Map<String, String> TAGS = new HashMap<>();

	static {
		String[][] pairs = {
				{"adventure", "Приключения"}, {"cursed", "Проклятое"}, {"decoration", "Декор"}, {"economy", "Экономика"},
				{"equipment", "Снаряжение"}, {"food", "Еда"}, {"game-mechanics", "Механики"}, {"library", "Библиотека"},
				{"magic", "Магия"}, {"management", "Управление"}, {"minigame", "Мини-игры"}, {"mobs", "Мобы"},
				{"optimization", "Оптимизация"}, {"social", "Социальное"}, {"storage", "Хранение"}, {"technology", "Технологии"},
				{"transportation", "Транспорт"}, {"utility", "Утилиты"}, {"worldgen", "Генерация мира"}, {"quests", "Квесты"},
				{"fabric", "Fabric"}, {"quilt", "Quilt"}, {"forge", "Forge"}, {"neoforge", "NeoForge"},
		};

		for (String[] p : pairs) {
			TAGS.put(p[0], p[1]);
		}
	}

	private Fmt() { }

	/** Грубо превращает markdown-описание Modrinth в читаемый текст: без картинок, ссылок, html и разметки. */
	public static String stripMarkdown(String md) {
		String s = md.replace("\r", "");
		s = s.replaceAll("(?s)<!--.*?-->", "");
		s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", ""); // картинки
		s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1"); // ссылки -> текст
		s = s.replaceAll("<[^>]+>", ""); // html-теги
		s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", ""); // заголовки
		s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "- "); // маркеры списков
		s = s.replaceAll("(\\*\\*|\\*|`|~~)", ""); // выделение
		s = s.replaceAll("[ \\t]+\n", "\n");
		s = s.replaceAll("\n{3,}", "\n\n");

		return s.trim();
	}

	/** 36743 -> "36.7K", 272714633 -> "272.71M" (как на сайте Modrinth). */
	public static String compact(long n) {
		if (n < 1_000) return Long.toString(n);
		if (n < 1_000_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
		if (n < 1_000_000_000) return String.format(Locale.ROOT, "%.2fM", n / 1_000_000.0);

		return String.format(Locale.ROOT, "%.2fB", n / 1_000_000_000.0);
	}

	public static String ago(String iso) {
		if (iso == null) return "";

		Instant then;

		try {
			then = OffsetDateTime.parse(iso).toInstant();
		} catch (RuntimeException e) {
			return "";
		}

		long seconds = Math.max(0, Duration.between(then, Instant.now()).getSeconds());

		if (seconds < 60) return "только что";
		if (seconds < 3600) return plural(seconds / 60, "минуту", "минуты", "минут") + " назад";
		if (seconds < 86_400) return plural(seconds / 3600, "час", "часа", "часов") + " назад";
		if (seconds < 86_400L * 30) return plural(seconds / 86_400, "день", "дня", "дней") + " назад";
		if (seconds < 86_400L * 365) return plural(seconds / (86_400L * 30), "месяц", "месяца", "месяцев") + " назад";

		return plural(seconds / (86_400L * 365), "год", "года", "лет") + " назад";
	}

	private static String plural(long n, String one, String few, String many) {
		long mod100 = n % 100;
		long mod10 = n % 10;
		String word = mod100 >= 11 && mod100 <= 14 ? many : mod10 == 1 ? one : mod10 >= 2 && mod10 <= 4 ? few : many;

		return n + " " + word;
	}

	/** Категории Modrinth, по которым можно фильтровать каталог (без загрузчиков). */
	public static final String[] FILTER_CATEGORIES = {"adventure", "cursed", "decoration", "economy", "equipment", "food", "game-mechanics",
			"library", "magic", "management", "minigame", "mobs", "optimization", "social", "storage", "technology", "transportation", "utility", "worldgen"};

	/** Русская подпись категории; незнакомую показывает с заглавной буквы. */
	public static String categoryLabel(String id) {
		String t = TAGS.get(id);

		return t != null ? t : id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	/** Теги карточки: окружение (клиент/сервер) и категории, по-русски где знаем перевод. */
	public static java.util.List<String> tags(ModrinthProject p) {
		java.util.List<String> tags = new java.util.ArrayList<>();

		boolean client = p.clientSide != null && !p.clientSide.equals("unsupported");
		boolean server = p.serverSide != null && !p.serverSide.equals("unsupported");

		if (client && server) tags.add("Клиент и сервер");
		else if (client) tags.add("Клиент");
		else if (server) tags.add("Сервер");

		for (String c : p.categories) {
			tags.add(TAGS.getOrDefault(c, c.isEmpty() ? c : Character.toUpperCase(c.charAt(0)) + c.substring(1)));
		}

		return tags;
	}
}
