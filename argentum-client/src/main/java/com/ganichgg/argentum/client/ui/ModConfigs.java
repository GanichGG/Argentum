package com.ganichgg.argentum.client.ui;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.ganichgg.argentum.client.config.ConfigDoc;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.minecraft.class_437;

/**
 * Настройки установленного мода. Сначала ищем экран настроек по соглашению Mod Menu (entrypoint {@code modmenu},
 * метод {@code getModConfigScreenFactory}) — через рефлексию, чтобы не зависеть от самого Mod Menu. Если экрана нет,
 * находим файл настроек в {@code config/} и открываем его системной программой.
 */
public final class ModConfigs {
	private ModConfigs() { }

	/** Id загруженного мода, чей jar называется {@code fileName}; null — мод не загружен (ещё не перезапускали игру). */
	public static String modIdForFile(String fileName) {
		if (fileName == null) {
			return null;
		}

		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModOrigin origin = mod.getOrigin();

			if (origin.getKind() != ModOrigin.Kind.PATH) {
				continue;
			}

			for (Path path : origin.getPaths()) {
				Path name = path.getFileName();

				if (name != null && name.toString().equals(fileName)) {
					return mod.getMetadata().getId();
				}
			}
		}

		return null;
	}

	/** Экран настроек от мода (через Mod Menu API) либо null, если мод его не предоставляет. */
	public static class_437 screen(String modId, class_437 parent) {
		Object factory = factory(modId);

		if (factory == null) {
			return null;
		}

		try {
			for (Class<?> iface : factory.getClass().getInterfaces()) {
				Method create = findCreate(iface);

				if (create != null) {
					Object screen = create.invoke(factory, parent);

					return screen instanceof class_437 ? (class_437) screen : null;
				}
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.GENERAL, "Экран настроек мода " + modId + " не открылся", t);
		}

		return null;
	}

	/** Есть ли у мода свой экран настроек (проверяем без создания самого экрана). */
	public static boolean hasScreen(String modId) {
		return factory(modId) != null;
	}

	private static Method findCreate(Class<?> iface) {
		try {
			return iface.getMethod("create", class_437.class);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	private static Object factory(String modId) {
		if (modId == null) {
			return null;
		}

		try {
			ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElse(null);

			if (mod == null) {
				return null;
			}

			if (!(mod.getMetadata() instanceof LoaderModMetadata)) {
				return null;
			}

			List<EntrypointMetadata> entries = ((LoaderModMetadata) mod.getMetadata()).getEntrypoints("modmenu");

			for (EntrypointMetadata entry : entries) {
				String value = entry.getValue();

				if (value.contains("::")) {
					continue;
				}

				Object api = Class.forName(value, true, ModConfigs.class.getClassLoader()).getDeclaredConstructor().newInstance();
				Method getter = api.getClass().getMethod("getModConfigScreenFactory");

				// интерфейсный метод по умолчанию возвращает «ничего» — такой мод экрана не предоставляет
				if (getter.getDeclaringClass().isInterface()) {
					continue;
				}

				Object factory = getter.invoke(api);

				if (factory != null) {
					return factory;
				}
			}
		} catch (Throwable t) {
			// нет Mod Menu рядом с модом — его API-классов не найти; считаем, что экрана нет
		}

		return null;
	}

	/**
	 * Файлы настроек мода в config/: всё, что называется как id мода или slug проекта (файл или папка с файлами),
	 * сначала поддерживаемые встроенным редактором, потом остальные. Пусто — не нашли.
	 */
	public static List<Path> configFiles(String modId, String slug) {
		Path dir = FabricLoader.getInstance().getConfigDir();
		List<Path> found = new ArrayList<>();

		if (!Files.isDirectory(dir)) {
			return found;
		}

		String a = modId == null ? null : modId.toLowerCase(Locale.ROOT);
		String b = slug == null ? null : slug.toLowerCase(Locale.ROOT);

		try (Stream<Path> top = Files.list(dir)) {
			for (Path p : (Iterable<Path>) top::iterator) {
				String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
				String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
				boolean match = stem.equals(a) || stem.equals(b)
						|| (a != null && (name.startsWith(a + "-") || name.startsWith(a + "_") || name.startsWith(a + ".")))
						|| (b != null && (name.startsWith(b + "-") || name.startsWith(b + "_") || name.startsWith(b + ".")));

				if (!match) {
					continue;
				}

				if (Files.isDirectory(p)) {
					try (Stream<Path> inner = Files.walk(p, 3)) {
						inner.filter(Files::isRegularFile).limit(20).forEach(found::add);
					}
				} else {
					found.add(p);
				}
			}
		} catch (IOException e) {
			return found;
		}

		// сначала файлы, которые редактор умеет читать, а среди них — настоящие настройки, а не служебные отпечатки/кэши
		found.sort((x, y) -> Integer.compare(configRank(x), configRank(y)));

		return found;
	}

	private static int configRank(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		int rank = ConfigDoc.supports(p) ? 0 : 10;

		if (n.contains("fingerprint") || n.contains("cache") || n.contains("state") || n.contains("history") || n.contains("hash")) {
			rank += 5;
		} else if (n.contains("option") || n.contains("config") || n.contains("settings") || n.contains("client")) {
			rank -= 1;
		}

		return rank;
	}

	/** Открывает файл/папку программой по умолчанию. */
	public static void openExternally(Path path) throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String target = path.toAbsolutePath().toString();

		if (os.contains("win")) {
			new ProcessBuilder("cmd", "/c", "start", "", target).start();
		} else if (os.contains("mac")) {
			new ProcessBuilder("open", target).start();
		} else {
			new ProcessBuilder("xdg-open", target).start();
		}
	}
}
