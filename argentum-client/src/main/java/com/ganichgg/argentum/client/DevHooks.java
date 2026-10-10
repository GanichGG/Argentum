package com.ganichgg.argentum.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.hotload.HotLoadReport;
import net.fabricmc.loader.impl.hotload.HotLoader;

/**
 * Хуки для разработки: файл-флаг в папке игры запускает проверку без ввода с клавиатуры/мыши.
 * {@code .argentum-hotload-test} — по пути jar на строку; hot-loader пробует подключить их и пишет итог в
 * {@code .argentum/hotload-report.txt}.
 */
public final class DevHooks {
	private DevHooks() { }

	public static void runHotloadTest() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path flag = gameDir.resolve(".argentum-hotload-test");

		if (!Files.exists(flag)) {
			return;
		}

		StringBuilder out = new StringBuilder();

		try {
			List<Path> jars = new ArrayList<>();

			for (String line : Files.readAllLines(flag, StandardCharsets.UTF_8)) {
				if (!line.trim().isEmpty()) {
					jars.add(Paths.get(line.trim()));
				}
			}

			Files.delete(flag);
			HotLoadReport report = HotLoader.load(jars);

			for (HotLoadReport.Entry e : report.entries()) {
				out.append(e.status).append(' ').append(e.modId).append(" (").append(e.name).append(") ").append(e.notes).append('\n');
			}

			out.append("SUMMARY: ").append(report.summary()).append('\n');
		} catch (Throwable t) {
			out.append("ERROR: ").append(t).append('\n');

			for (StackTraceElement el : t.getStackTrace()) {
				out.append("  at ").append(el).append('\n');

				if (out.length() > 6000) {
					break;
				}
			}
		}

		try {
			Path dir = gameDir.resolve(".argentum");
			Files.createDirectories(dir);
			Files.write(dir.resolve("hotload-report.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			// неважно
		}
	}
}
