/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.crash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор отчёта о падении игры без участия игрока: цепочка исключений («Caused by»), первопричина (самое глубокое
 * исключение, а не обёртки над ним), вид проблемы и, если итоговая ошибка ничего не говорит, первая настоящая ошибка
 * из журнала, с которой всё началось. Только чтение текста — без файлов и без загрузчика, поэтому легко проверяется.
 */
public final class CrashAnalysis {
	/** Вид проблемы — от него зависит, как объяснить её игроку и что предложить. */
	public enum Kind {
		/** Мод не смог изменить код игры (Mixin): версия мода не подходит игре или другому моду. */
		MIXIN,
		/** Не найден класс: нет нужного мода-зависимости или мод для другой версии. */
		MISSING_CLASS,
		/** Нет метода/поля: мод собран под другую версию игры или другого мода. */
		INCOMPATIBLE,
		/** Не хватило памяти. */
		OUT_OF_MEMORY,
		/** Мод требует более новую Java. */
		JAVA_VERSION,
		/** Испорчен файл настроек мода. */
		CONFIG,
		/** Видеодрайвер или OpenGL. */
		GRAPHICS,
		/** Ошибка в коде мода. */
		MOD_ERROR,
		/** Не удалось понять. */
		UNKNOWN
	}

	/** Одно исключение цепочки: класс, сообщение и строки стека («пакет.Класс.метод»). */
	public static final class Cause {
		public final String type;
		public final String message;
		public final List<String> frames;

		Cause(String type, String message, List<String> frames) {
			this.type = type;
			this.message = message;
			this.frames = frames;
		}

		/** Короткое имя класса: {@code NullPointerException}. */
		public String simpleType() {
			int dot = type.lastIndexOf('.');

			return dot < 0 ? type : type.substring(dot + 1);
		}

		@Override
		public String toString() {
			return message.isEmpty() ? type : type + ": " + message;
		}
	}

	private static final Pattern HEADER = Pattern.compile("^(?:Caused by: )?([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)+)(?::\\s?(.*))?$");
	private static final Pattern FRAME = Pattern.compile("^\\s+at (?:[\\w.$@-]+/)*?([\\w$.<>-]+)\\((.*?)\\)(?:\\s.*)?$");
	/** Обработчик миксина, вклеенный в класс игры: в имени метода Fabric указывает id мода. */
	public static final Pattern MIXIN_HANDLER = Pattern.compile("(?:^|[.$])(?:handler|redirect|modify|wrapOperation|localvar|constant|wrapWithCondition|modifyExpressionValue|modifyReturnValue|modifyArg|modifyArgs|modifyVariable)\\$[a-z]{3}\\d{3}\\$([A-Za-z0-9_\\-]+)\\$");
	private static final Pattern FROM_MOD = Pattern.compile("from mod ([A-Za-z0-9_.\\-]+)");
	private static final Pattern LOG_ERROR = Pattern.compile("^\\[[^\\]]*\\] \\[[^\\]]*/(ERROR|FATAL)\\]");

	/** Обёртки: сами ничего не объясняют, смотрим глубже. */
	private static final Set<String> WRAPPERS = new HashSet<>(Arrays.asList(
			"java.util.concurrent.CompletionException",
			"java.util.concurrent.ExecutionException",
			"java.lang.reflect.InvocationTargetException",
			"java.lang.reflect.UndeclaredThrowableException",
			"java.lang.ExceptionInInitializerError",
			"java.lang.BootstrapMethodError",
			"net.minecraft.class_148", // ReportedException
			"net.minecraft.util.crash.CrashException",
			"org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError"));

	/** Пакеты игры, Java и самого загрузчика: их классы в стеке не указывают на мод. */
	private static final String[] PLATFORM = {
		"net.minecraft.", "com.mojang.", "java.", "javax.", "jdk.", "sun.", "org.spongepowered.", "net.fabricmc.loader.",
		"org.lwjgl.", "it.unimi.", "com.google.", "org.apache.", "io.netty.", "org.slf4j.", "org.objectweb.", "oshi.",
		"com.llamalad7.", "net.fabricmc.api.", "com.ganichgg.argentum.core."
	};

	public final List<Cause> chain;
	/** Первопричина (самое глубокое содержательное исключение) или null, если стек не нашёлся. */
	public final Cause root;
	public final Kind kind;
	/** Мод, названный в ошибке Mixin («from mod x»), или null. */
	public final String mixinMod;
	/**
	 * Первая ошибка журнала, если итоговая ошибка общая (в стеке нет кода модов): с неё обычно всё и началось; иначе
	 * null.
	 */
	public final String firstLogError;

	private CrashAnalysis(List<Cause> chain, Cause root, Kind kind, String mixinMod, String firstLogError) {
		this.chain = chain;
		this.root = root;
		this.kind = kind;
		this.mixinMod = mixinMod;
		this.firstLogError = firstLogError;
	}

	/**
	 * @param report текст отчёта о падении ({@code crash-reports/*.txt})
	 * @param log    журнал этого запуска ({@code logs/latest.log}) или null
	 */
	public static CrashAnalysis analyze(String report, String log) {
		List<Cause> chain = parseChain(report);
		Cause root = root(chain);
		String mixinMod = null;
		Matcher m = FROM_MOD.matcher(mainBlock(report));

		if (m.find()) {
			mixinMod = m.group(1);
		}

		Kind kind = classify(chain, root, mixinMod);
		String firstError = null;

		if (log != null && (root == null || !hasModFrames(root)) && mixinMod == null) {
			firstError = firstLogError(log);
		}

		return new CrashAnalysis(chain, root, kind, mixinMod, firstError);
	}

	/** Начало отчёта до подробного разбора по потокам: там основная ошибка и её цепочка. */
	static String mainBlock(String report) {
		int end = report.length();

		for (String stop : new String[] {"A detailed walkthrough of the error", "\n-- Head --", "\n-- System Details --"}) {
			int i = report.indexOf(stop);

			if (i >= 0 && i < end) {
				end = i;
			}
		}

		int start = report.indexOf("Description:");

		if (start >= 0) {
			int nl = report.indexOf('\n', start);
			start = nl < 0 ? end : nl + 1;
		} else {
			start = 0;
		}

		return start < end ? report.substring(start, end) : report.substring(0, end);
	}

	/** Цепочка исключений основной ошибки: первое, затем каждое «Caused by»; подавленные («Suppressed») пропускаются. */
	static List<Cause> parseChain(String report) {
		List<Cause> chain = new ArrayList<>();
		String type = null;
		StringBuilder message = null;
		List<String> frames = null;
		boolean inSuppressed = false;

		for (String raw : mainBlock(report).split("\r?\n")) {
			String line = raw.replace("\t", "    ");

			if (line.trim().isEmpty()) {
				continue;
			}

			Matcher frame = FRAME.matcher(line);

			if (frame.matches()) {
				if (type != null && !inSuppressed) {
					frames.add(frame.group(1));
				}

				continue;
			}

			String trimmed = line.trim();

			if (trimmed.startsWith("...") && trimmed.endsWith("more")) {
				continue;
			}

			if (trimmed.startsWith("Suppressed: ")) {
				inSuppressed = true;
				continue;
			}

			boolean indented = line.startsWith(" ");
			Matcher header = HEADER.matcher(trimmed);

			if (!indented && header.matches() && (chain.isEmpty() && type == null || trimmed.startsWith("Caused by: "))) {
				if (type != null) {
					chain.add(new Cause(type, message.toString().trim(), frames));
				}

				inSuppressed = false;
				type = header.group(1);
				message = new StringBuilder(header.group(2) == null ? "" : header.group(2));
				frames = new ArrayList<>();
				continue;
			}

			if (indented && trimmed.startsWith("Caused by: ")) {
				continue; // причина внутри подавленного исключения
			}

			// продолжение многострочного сообщения
			if (type != null && !inSuppressed && frames.isEmpty()) {
				message.append('\n').append(trimmed);
			}
		}

		if (type != null) {
			chain.add(new Cause(type, message.toString().trim(), frames));
		}

		return chain;
	}

	/** Самое глубокое исключение, которое не обёртка; если вся цепочка — обёртки, то самое глубокое. */
	static Cause root(List<Cause> chain) {
		if (chain.isEmpty()) {
			return null;
		}

		for (int i = chain.size() - 1; i >= 0; i--) {
			Cause c = chain.get(i);

			if (!isWrapper(c, i + 1 < chain.size() ? chain.get(i + 1) : null)) {
				return c;
			}
		}

		return chain.get(chain.size() - 1);
	}

	private static boolean isWrapper(Cause c, Cause next) {
		if (WRAPPERS.contains(c.type)) {
			return true;
		}

		// RuntimeException("x.y.SomeError: ...") — просто пересказ следующей причины
		return next != null && c.type.equals("java.lang.RuntimeException") && c.message.startsWith(next.type);
	}

	static Kind classify(List<Cause> chain, Cause root, String mixinMod) {
		if (root == null) {
			return Kind.UNKNOWN;
		}

		for (Cause c : chain) {
			String t = c.type;

			if (t.equals("java.lang.OutOfMemoryError")) {
				return Kind.OUT_OF_MEMORY;
			}

			if (t.equals("java.lang.UnsupportedClassVersionError")) {
				return Kind.JAVA_VERSION;
			}
		}

		String all = String.join("\n", chainTexts(chain)).toLowerCase(Locale.ROOT);

		if (mixinMod != null || root.type.startsWith("org.spongepowered.asm.mixin.") || all.contains("mixin apply failed")
				|| all.contains("mixin transformation of")) {
			return Kind.MIXIN;
		}

		String rt = root.type;

		if (rt.equals("java.lang.NoClassDefFoundError") || rt.equals("java.lang.ClassNotFoundException")) {
			return Kind.MISSING_CLASS;
		}

		if (rt.equals("java.lang.NoSuchMethodError") || rt.equals("java.lang.NoSuchFieldError") || rt.equals("java.lang.AbstractMethodError")
				|| rt.equals("java.lang.IncompatibleClassChangeError")) {
			return Kind.INCOMPATIBLE;
		}

		if (rt.startsWith("com.google.gson.") || rt.contains("JsonSyntaxException") || rt.contains("MalformedJson")
				|| rt.startsWith("com.electronwill.nightconfig.") || rt.contains("Toml") || rt.contains("Yaml")
				|| root.message.toLowerCase(Locale.ROOT).contains(".json5") || isConfigFrames(root)) {
			return Kind.CONFIG;
		}

		if (all.contains("glfw error") || all.contains("opengl") || all.contains("pixel format not accelerated")
				|| all.contains("wgl") || all.contains("no opengl context") || all.contains("exception_access_violation")) {
			return Kind.GRAPHICS;
		}

		if (hasModFrames(root)) {
			return Kind.MOD_ERROR;
		}

		return Kind.UNKNOWN;
	}

	private static boolean isConfigFrames(Cause root) {
		for (int i = 0; i < Math.min(8, root.frames.size()); i++) {
			String f = root.frames.get(i).toLowerCase(Locale.ROOT);

			if ((f.contains("config") || f.contains("settings")) && (f.contains("load") || f.contains("read") || f.contains("parse"))) {
				return root.type.startsWith("java.lang.IllegalStateException") || root.type.contains("Json") || root.type.contains("Parse");
			}
		}

		return false;
	}

	private static List<String> chainTexts(List<Cause> chain) {
		List<String> out = new ArrayList<>();

		for (Cause c : chain) {
			out.add(c.toString());
		}

		return out;
	}

	/** В первых строках стека есть код не игры и не Java — значит, ошибка в моде. */
	static boolean hasModFrames(Cause c) {
		for (int i = 0; i < Math.min(12, c.frames.size()); i++) {
			if (!isPlatform(c.frames.get(i)) || MIXIN_HANDLER.matcher(c.frames.get(i)).find()) {
				return true;
			}
		}

		return false;
	}

	public static boolean isPlatform(String frame) {
		for (String p : PLATFORM) {
			if (frame.startsWith(p)) {
				return true;
			}
		}

		// встроенные классы миксинов и лямбды игры
		return frame.startsWith("$") || frame.startsWith("net.minecraft");
	}

	/**
	 * Первая ошибка в журнале этого запуска, до самого отчёта о падении: при каскаде (один мод сломал реестр — дальше
	 * всё сыплется) итоговая ошибка общая, а настоящий источник — здесь. Строка плюс первая строка исключения под ней.
	 */
	static String firstLogError(String log) {
		String[] lines = log.split("\r?\n");
		int crashAt = lines.length;

		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("---- Minecraft Crash Report ----") || lines[i].contains("Preparing crash report")) {
				crashAt = i;
				break;
			}
		}

		for (int i = 0; i < crashAt; i++) {
			if (!LOG_ERROR.matcher(lines[i]).find()) {
				continue;
			}

			String l = lines[i];

			// шум, который бывает почти у всех и ничего не ломает
			String low = l.toLowerCase(Locale.ROOT);

			if (low.contains("realms") || low.contains("telemetry") || low.contains("narrator") || low.contains("unable to resolve texture")
					|| low.contains("missing sound") || low.contains("unknown custom packet") || low.contains("couldn't parse data file")
					&& low.contains("advancement")) {
				continue;
			}

			StringBuilder sb = new StringBuilder(l.trim());

			if (i + 1 < crashAt && HEADER.matcher(lines[i + 1].trim()).matches() && !lines[i + 1].startsWith("[")) {
				sb.append('\n').append(lines[i + 1].trim());
			}

			String text = sb.toString();

			return text.length() > 400 ? text.substring(0, 397) + "..." : text;
		}

		return null;
	}

	/** Строки стека первопричины, по которым ищется мод-виновник (в порядке от места ошибки). */
	public List<String> rootFrames() {
		return root == null ? Collections.emptyList() : root.frames;
	}
}
