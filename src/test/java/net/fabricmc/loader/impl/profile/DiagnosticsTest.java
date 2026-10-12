/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DiagnosticsTest {
	private String oldHome;
	private String oldUser;

	@BeforeEach
	public void fakeUser() {
		oldHome = System.getProperty("user.home");
		oldUser = System.getProperty("user.name");
		System.setProperty("user.home", "C:\\Users\\bobsmith");
		System.setProperty("user.name", "bobsmith");
	}

	@AfterEach
	public void restore() {
		System.setProperty("user.home", oldHome);
		System.setProperty("user.name", oldUser);
	}

	@Test
	public void homeFolderIsRemovedInAnyPathStyle() {
		Assertions.assertEquals("<home>\\AppData\\mods", Diagnostics.clean("C:\\Users\\bobsmith\\AppData\\mods"));
		Assertions.assertEquals("<home>/AppData/mods", Diagnostics.clean("C:/Users/bobsmith/AppData/mods"));
		Assertions.assertEquals("\"<home>\\\\AppData\"", Diagnostics.clean("\"C:\\\\Users\\\\bobsmith\\\\AppData\""));
	}

	@Test
	public void userNameInOtherPathsIsRemoved() {
		Assertions.assertEquals("D:\\Games\\<user>\\saves", Diagnostics.clean("D:\\Games\\bobsmith\\saves"));
		Assertions.assertEquals("/srv/<user>/saves", Diagnostics.clean("/srv/bobsmith/saves"));
	}

	@Test
	public void ordinaryTextIsUntouched() {
		Assertions.assertEquals("[10:00:00] [main/INFO]: Loading Minecraft 1.21.1", Diagnostics.clean("[10:00:00] [main/INFO]: Loading Minecraft 1.21.1"));
	}
}
