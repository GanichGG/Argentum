/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.client.ui;

import net.minecraft.class_332;

/** Маленькие пиксельные иконки, нарисованные прямоугольниками 1×1 — без текстур и шрифтов. */
public final class PixelIcons {
	public static final String[] SEARCH = {
			"..###....",
			".#...#...",
			"#.....#..",
			"#.....#..",
			"#.....#..",
			".#...#...",
			"..###.#..",
			".......#.",
			"........#",
	};

	public static final String[] DOWNLOAD = {
			"...#...",
			"...#...",
			".#.#.#.",
			"..###..",
			"...#...",
			".......",
			"#######",
	};

	public static final String[] HEART = {
			".##.##.",
			"#######",
			"#######",
			".#####.",
			"..###..",
			"...#...",
	};

	public static final String[] CLOCK = {
			"..###..",
			".#...#.",
			"#..#..#",
			"#..#..#",
			"#..##.#",
			".#...#.",
			"..###..",
	};

	public static final String[] CLOSE = {
			"#.....#",
			".#...#.",
			"..#.#..",
			"...#...",
			"..#.#..",
			".#...#.",
			"#.....#",
	};

	public static final String[] GEAR = {
			"...###...",
			".#.###.#.",
			".#######.",
			"###...###",
			"###...###",
			"###...###",
			".#######.",
			".#.###.#.",
			"...###...",
	};

	public static final String[] POWER = {
			"...#...",
			"#..#..#",
			"#..#..#",
			"#.....#",
			"#.....#",
			".#...#.",
			"..###..",
	};

	public static final String[] UP = {
			"...#...",
			"..###..",
			".#.#.#.",
			"...#...",
			"...#...",
			"...#...",
	};

	public static final String[] ARROW_DOWN = {
			"#.....#",
			".#...#.",
			"..#.#..",
			"...#...",
	};

	/** Позиции 8 точек пиксельного спиннера (кольцо 3×3 квадрата с шагом 4). */
	private static final int[][] SPINNER = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}};

	private PixelIcons() { }

	/**
	 * Пиксельный спиннер 11×11: «хвост» из восьми точек бежит по кольцу, к концу хвоста точки тускнеют.
	 * Двигается шагами (так выглядит пиксельно), около 11 шагов в секунду.
	 */
	public static void spinner(class_332 ctx, int x, int y, int color) {
		int head = (int) ((System.currentTimeMillis() / 90) % 8);

		for (int i = 0; i < 8; i++) {
			int age = (head - i + 8) % 8; // 0 — голова, 7 — самый старый
			int alpha = Math.max(0x30, 0xFF - age * 0x2A);
			int c = (alpha << 24) | (color & 0x00FFFFFF);
			int px = x + SPINNER[i][0] * 4;
			int py = y + SPINNER[i][1] * 4;
			ctx.method_25294(px, py, px + 3, py + 3, c);
		}
	}

	public static int width(String[] icon) {
		return icon[0].length();
	}

	public static int height(String[] icon) {
		return icon.length;
	}

	/** class_332 = DrawContext; method_25294 = fill. */
	public static void draw(class_332 ctx, String[] icon, int x, int y, int color) {
		for (int row = 0; row < icon.length; row++) {
			String line = icon[row];

			for (int col = 0; col < line.length(); col++) {
				if (line.charAt(col) == '#') {
					ctx.method_25294(x + col, y + row, x + col + 1, y + row + 1, color);
				}
			}
		}
	}
}
