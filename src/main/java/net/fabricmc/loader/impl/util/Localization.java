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

package net.fabricmc.loader.impl.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

import com.ganichgg.argentum.core.i18n.Lang;

public final class Localization {
	// ArgentumLoader: the bundle follows the language of the game (see Lang): Messages_ru is the Russian original, the
	// root bundle is English and is also used for languages without a translation. Never falls back to the OS locale.
	public static final ResourceBundle BUNDLE = createBundle("net.fabricmc.loader.Messages", gameLocale());
	public static final ResourceBundle ROOT_LOCALE_BUNDLE = createBundle("net.fabricmc.loader.Messages", Locale.ROOT);

	private static Locale gameLocale() {
		String code = Lang.language(); // например ru_ru, en_us, ja_jp

		String[] parts = code.split("_");

		if (parts.length >= 2) {
			return new Locale(parts[0], parts[1].toUpperCase(Locale.ROOT));
		}

		return parts.length == 1 && !parts[0].isEmpty() ? new Locale(parts[0]) : Locale.ROOT;
	}

	public static String format(String key, Object... args) {
		String pattern = BUNDLE.getString(key);

		if (args.length == 0) {
			return pattern;
		} else {
			return MessageFormat.format(pattern, args);
		}
	}

	public static String formatRoot(String key, Object... args) {
		String pattern = ROOT_LOCALE_BUNDLE.getString(key);

		if (args.length == 0) {
			return pattern;
		} else {
			return MessageFormat.format(pattern, args);
		}
	}

	private static ResourceBundle createBundle(String name, Locale locale) {
		if (System.getProperty("java.version", "").startsWith("1.")) { // below java 9
			return ResourceBundle.getBundle(name, locale, new ResourceBundle.Control() {
				@Override
				public Locale getFallbackLocale(String baseName, Locale locale) {
					return null; // не подменять язык игры языком системы
				}

				@Override
				public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
						throws IllegalAccessException, InstantiationException, IOException {
					if (format.equals("java.properties")) {
						InputStream is = loader.getResourceAsStream(toResourceName(toBundleName(baseName, locale), "properties"));

						if (is != null) {
							try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
								return new PropertyResourceBundle(reader);
							}
						}
					}

					return super.newBundle(baseName, locale, format, loader, reload);
				};
			});
		} else { // java 9 and later
			return ResourceBundle.getBundle(name, locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
		}
	}
}
