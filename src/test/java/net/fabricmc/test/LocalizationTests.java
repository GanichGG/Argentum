/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.test;

import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import net.fabricmc.loader.impl.util.Localization;

public class LocalizationTests {
	private static ResourceBundle bundle(Locale locale) {
		return ResourceBundle.getBundle("net.fabricmc.loader.Messages", locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
	}

	private static Set<String> keys(ResourceBundle bundle) {
		return new HashSet<>(java.util.Collections.list(bundle.getKeys()));
	}

	@Test
	public void formatRoot() {
		// ArgentumLoader: the root bundle is English (the language of the logs and the fallback for unknown languages)
		Assertions.assertEquals("client", Localization.formatRoot("environment.client"));
		Assertions.assertEquals("Install A, B.", Localization.formatRoot("resolution.solution.addMod", "A", "B"));
	}

	@Test
	public void russianBundleIsComplete() {
		ResourceBundle root = bundle(Locale.ROOT);
		ResourceBundle ru = bundle(new Locale("ru"));

		Assertions.assertEquals("клиент", ru.getString("environment.client"));
		// every message must exist in both languages, otherwise the error window would show a missing key
		Assertions.assertEquals(keys(root), keys(ru));
	}
}
