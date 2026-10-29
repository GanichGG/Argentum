/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.log;

/** Раздел журнала Argentum Core; платформа сопоставляет его со своими разделами. */
public final class LogCategory {
	public static final LogCategory GENERAL = new LogCategory("General");
	public static final LogCategory MODRINTH = new LogCategory("Modrinth");

	public final String name;

	private LogCategory(String name) {
		this.name = name;
	}
}
