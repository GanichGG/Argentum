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

	private PixelIcons() { }

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
