/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package com.ganichgg.argentum.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.ganichgg.argentum.client.handoff.Handoff;

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

	/**
	 * Проверка бесшовного применения без ввода: флаг {@code .argentum-handoff-test} запускает подготовку копии, а
	 * когда она готова — переключение. Ход пишется в {@code .argentum/handoff-test.log}.
	 */
	public static void runHandoffTest(net.minecraft.class_310 client) {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path flag = gameDir.resolve(".argentum-handoff-test");

		if (Handoff.isHiddenChild() || !Files.exists(flag)) {
			return;
		}

		try {
			Files.delete(flag);
		} catch (IOException ignored) {
			return;
		}

		Path log = gameDir.resolve(".argentum").resolve("handoff-test.log");
		long start = System.currentTimeMillis();

		Thread t = new Thread(() -> {
			try {
				Thread.sleep(20_000); // время подложить мод в папку mods уже после старта основной копии
				Handoff.requestPrepare();
				while (System.currentTimeMillis() - start < 320_000) {
					Handoff.State st = Handoff.state();
					append(log, (System.currentTimeMillis() - start) + "ms state=" + st);

					if (st == Handoff.State.READY) {
						Thread.sleep(3000); // время сделать снимок «до»
						client.execute(() -> append(log, "swap -> " + Handoff.swap(client, true, "Тест применения")));
						return;
					}

					if (st == Handoff.State.FAILED) {
						append(log, "failed: " + Handoff.failure());
						return;
					}

					Thread.sleep(2000);
				}
			} catch (InterruptedException ignored) {
				// конец
			}
		}, "argentum-dev-handoff");
		t.setDaemon(true);
		t.start();
	}

	private static void append(Path file, String line) {
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// неважно
		}
	}

	/** Dev: сохраняет текущий кадр игры в .argentum/shots/<name>.png. */
	public static void dumpFrame(net.minecraft.class_310 client, String name) {
		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve(".argentum").resolve("shots");
			Files.createDirectories(dir);

			try (net.minecraft.class_1011 image = net.minecraft.class_318.method_1663(client.method_1522())) {
				image.method_4314(dir.resolve(name + ".png"));
			}
		} catch (IOException | RuntimeException ignored) {
			// неважно
		}
	}
}
