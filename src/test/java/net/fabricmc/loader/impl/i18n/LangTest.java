/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LangTest {
	@AfterEach
	public void reset() {
		System.clearProperty("argentum.lang");
		Lang.refresh();
	}

	private static void use(String code) {
		System.setProperty("argentum.lang", code);
		Lang.refresh();
	}

	@Test
	public void russianKeepsTheOriginals() {
		use("ru_ru");
		Assertions.assertEquals("Нечего загружать", Lang.tr("Нечего загружать"));
	}

	@Test
	public void englishTranslatesByOriginalText() {
		use("en_us");
		Assertions.assertEquals("Nothing to load", Lang.tr("Нечего загружать"));
	}

	@Test
	public void unknownLanguagesFallBackToEnglish() {
		use("xx_yy");
		Assertions.assertEquals("Nothing to load", Lang.tr("Нечего загружать"));
	}

	@Test
	public void missingTranslationShowsTheOriginal() {
		use("en_us");
		Assertions.assertEquals("Эта строка не переведена", Lang.tr("Эта строка не переведена"));
	}

	@Test
	public void argumentsAreFormatted() {
		use("en_us");
		Assertions.assertEquals("duplicate mod abc", Lang.tr("повторяющийся мод %s", "abc"));
	}

	@Test
	public void languageIsReadFromTheOverrideProperty() {
		use("ja_jp");
		Assertions.assertEquals("ja_jp", Lang.language());
	}
}
