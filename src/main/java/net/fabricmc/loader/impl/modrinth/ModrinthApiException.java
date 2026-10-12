/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.modrinth;

@SuppressWarnings("serial")
public class ModrinthApiException extends Exception {
	public ModrinthApiException(String message) {
		super(message);
	}

	public ModrinthApiException(String message, Throwable cause) {
		super(message, cause);
	}
}
