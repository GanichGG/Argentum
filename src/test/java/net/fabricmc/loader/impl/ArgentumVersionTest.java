/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;

public class ArgentumVersionTest {
	@Test
	public void humanVersionsMapToSemver() {
		Assertions.assertEquals("0.1.0-b.1", ArgentumVersion.toSemver("0.1B1"));
		Assertions.assertEquals("0.1.0-a.3", ArgentumVersion.toSemver("0.1A3"));
		Assertions.assertEquals("0.1.0", ArgentumVersion.toSemver("0.1"));
		Assertions.assertEquals("1.0.0", ArgentumVersion.toSemver("1.0"));
		Assertions.assertEquals("1.12.0-b.20", ArgentumVersion.toSemver("1.12B20"));
	}

	@Test
	public void wrongFormatsAreRejected() {
		for (String bad : new String[] {"", "1", "0.1b1", "0.1C1", "0.1B", "0.1.0", "v0.1B1", "0.1B1-rc", "0.1 B1"}) {
			Assertions.assertThrows(IllegalArgumentException.class, () -> ArgentumVersion.toSemver(bad), bad);
		}
	}

	@Test
	public void semverOrderIsAlphaBetaRelease() throws VersionParsingException {
		SemanticVersion a3 = SemanticVersion.parse(ArgentumVersion.toSemver("0.1A3"));
		SemanticVersion b1 = SemanticVersion.parse(ArgentumVersion.toSemver("0.1B1"));
		SemanticVersion b2 = SemanticVersion.parse(ArgentumVersion.toSemver("0.1B2"));
		SemanticVersion release = SemanticVersion.parse(ArgentumVersion.toSemver("0.1"));
		SemanticVersion next = SemanticVersion.parse(ArgentumVersion.toSemver("0.2A1"));

		Assertions.assertTrue(a3.compareTo(b1) < 0);
		Assertions.assertTrue(b1.compareTo(b2) < 0);
		Assertions.assertTrue(b2.compareTo(release) < 0);
		Assertions.assertTrue(release.compareTo(next) < 0);
	}

	@Test
	public void theDeclaredVersionIsValidAndPreReleasesAreRecognised() {
		Assertions.assertDoesNotThrow(ArgentumVersion::semver);
		Assertions.assertTrue(ArgentumVersion.isPreRelease("0.1A1"));
		Assertions.assertTrue(ArgentumVersion.isPreRelease("0.1B7"));
		Assertions.assertFalse(ArgentumVersion.isPreRelease("0.1"));
		Assertions.assertFalse(ArgentumVersion.isPreRelease("1.0"));
	}
}
