/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.log;

import java.util.Arrays;
import java.util.IllegalFormatException;

/**
 * Журнал Argentum Core с тем же видом вызовов, что и журнал Fabric Loader ({@code Log.warn(категория, "формат %s", аргументы)};
 * последний аргумент-исключение печатается со стеком). Куда писать, решает платформа ({@link #setSink}); по умолчанию —
 * в stderr.
 */
public final class Log {
	public enum Level { DEBUG, INFO, WARN, ERROR }

	/** Приёмник записей журнала. */
	public interface Sink {
		void log(Level level, LogCategory category, String message, Throwable exc);
	}

	private static volatile Sink sink = (level, category, message, exc) -> {
		System.err.println("[" + level + "] [Argentum/" + category.name + "] " + message);

		if (exc != null) {
			exc.printStackTrace();
		}
	};

	private Log() { }

	public static void setSink(Sink s) {
		sink = s;
	}

	public static void debug(LogCategory category, String format, Object... args) {
		log(Level.DEBUG, category, format, args);
	}

	public static void info(LogCategory category, String format, Object... args) {
		log(Level.INFO, category, format, args);
	}

	public static void warn(LogCategory category, String format, Object... args) {
		log(Level.WARN, category, format, args);
	}

	public static void error(LogCategory category, String format, Object... args) {
		log(Level.ERROR, category, format, args);
	}

	private static void log(Level level, LogCategory category, String format, Object... args) {
		Throwable exc = null;
		Object[] fmtArgs = args;

		if (args.length > 0 && args[args.length - 1] instanceof Throwable && count(format) < args.length) {
			exc = (Throwable) args[args.length - 1];
			fmtArgs = Arrays.copyOf(args, args.length - 1);
		}

		String message;

		try {
			message = fmtArgs.length == 0 ? format : String.format(format, fmtArgs);
		} catch (IllegalFormatException e) {
			message = format + " " + Arrays.toString(fmtArgs);
		}

		sink.log(level, category, message, exc);
	}

	/** Сколько подстановок %… ожидает формат (без %%). */
	private static int count(String format) {
		int n = 0;

		for (int i = 0; i < format.length() - 1; i++) {
			if (format.charAt(i) == '%') {
				if (format.charAt(i + 1) == '%') {
					i++;
				} else if (format.charAt(i + 1) != 'n') {
					n++;
				}
			}
		}

		return n;
	}
}
