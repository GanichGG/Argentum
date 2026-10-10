package com.ganichgg.argentum.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.class_1011;
import net.minecraft.class_1043;
import net.minecraft.class_2960;
import net.minecraft.class_310;

/**
 * Текст «до ресурсов игры» — тот же приём, что у раннего экрана NeoForge: свой встроенный шрифт,
 * растеризуем сами через STB (есть в LWJGL, который уже загружен), а не шрифтом Minecraft, который
 * появляется только в конце загрузки ресурсов. Строка один раз рисуется в текстуру и дальше просто
 * выводится картинкой.
 *
 * <p>Имена в intermediary (Yarn в скобках): class_1011 = NativeImage, class_1043 = NativeImageBackedTexture,
 * class_2960 = Identifier, class_310 = MinecraftClient.
 */
public final class EarlyText {
	/** Размер шрифта в пикселях текстуры; тексель = 1 единица GUI, поэтому пиксели резкие при любом масштабе GUI. */
	public static final int FONT_PX = 16;
	private static final String FONT_RESOURCE = "/assets/argentum-client/font/PIXY.ttf";

	/** Готовая строка: текстура и её размер в пикселях (= в единицах GUI при масштабе 1). */
	public static final class Line {
		public final class_2960 texture;
		public final int width;
		public final int height;

		Line(class_2960 texture, int width, int height) {
			this.texture = texture;
			this.width = width;
			this.height = height;
		}
	}

	private static final Map<String, Line> CACHE = new HashMap<>();
	private static STBTTFontinfo font;
	private static ByteBuffer fontData; // STB держит указатель на эти данные — должны жить всё время
	private static int counter;

	private EarlyText() { }

	/** Только из потока рендера (создаёт GL-текстуру). Возвращает null, если шрифт не удалось загрузить. */
	public static Line get(class_310 client, String text) {
		Line cached = CACHE.get(text);

		if (cached != null) {
			return cached;
		}

		if (!initFont()) {
			return null;
		}

		Line line = render(client, text);
		CACHE.put(text, line);

		return line;
	}

	private static boolean initFont() {
		if (font != null) {
			return true;
		}

		try (InputStream is = EarlyText.class.getResourceAsStream(FONT_RESOURCE)) {
			if (is == null) {
				return false;
			}

			byte[] bytes = is.readAllBytes();
			ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
			buf.put(bytes).flip();

			STBTTFontinfo info = STBTTFontinfo.create();

			if (!STBTruetype.stbtt_InitFont(info, buf)) {
				MemoryUtil.memFree(buf);
				return false;
			}

			fontData = buf;
			font = info;

			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static Line render(class_310 client, String text) {
		float scale = STBTruetype.stbtt_ScaleForMappingEmToPixels(font, FONT_PX);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer ascentBuf = stack.mallocInt(1);
			IntBuffer descentBuf = stack.mallocInt(1);
			IntBuffer gapBuf = stack.mallocInt(1);
			STBTruetype.stbtt_GetFontVMetrics(font, ascentBuf, descentBuf, gapBuf);

			int ascent = Math.round(ascentBuf.get(0) * scale);
			int descent = Math.round(descentBuf.get(0) * scale);
			int height = Math.max(1, ascent - descent);

			IntBuffer advance = stack.mallocInt(1);
			IntBuffer lsb = stack.mallocInt(1);
			int[] codepoints = text.codePoints().toArray();
			int width = 0;

			for (int cp : codepoints) {
				STBTruetype.stbtt_GetCodepointHMetrics(font, cp, advance, lsb);
				width += Math.round(advance.get(0) * scale);
			}

			width = Math.max(1, width);

			class_1011 image = new class_1011(class_1011.class_1012.field_4997, width, height, true); // NativeImage RGBA, обнулённый

			IntBuffer x0 = stack.mallocInt(1);
			IntBuffer y0 = stack.mallocInt(1);
			IntBuffer x1 = stack.mallocInt(1);
			IntBuffer y1 = stack.mallocInt(1);
			int pen = 0;

			for (int cp : codepoints) {
				STBTruetype.stbtt_GetCodepointHMetrics(font, cp, advance, lsb);
				STBTruetype.stbtt_GetCodepointBitmapBox(font, cp, scale, scale, x0, y0, x1, y1);

				int gw = x1.get(0) - x0.get(0);
				int gh = y1.get(0) - y0.get(0);

				if (gw > 0 && gh > 0) {
					ByteBuffer bitmap = MemoryUtil.memAlloc(gw * gh);

					try {
						STBTruetype.stbtt_MakeCodepointBitmap(font, bitmap, gw, gh, gw, scale, scale, cp);

						for (int gy = 0; gy < gh; gy++) {
							for (int gx = 0; gx < gw; gx++) {
								int alpha = bitmap.get(gy * gw + gx) & 0xFF;
								int px = pen + x0.get(0) + gx;
								int py = ascent + y0.get(0) + gy;

								if (alpha != 0 && px >= 0 && px < width && py >= 0 && py < height) {
									image.method_4305(px, py, (alpha << 24) | 0x00FFFFFF); // NativeImage.setColor (ABGR) — белый с альфой
								}
							}
						}
					} finally {
						MemoryUtil.memFree(bitmap);
					}
				}

				pen += Math.round(advance.get(0) * scale);
			}

			class_2960 id = class_2960.method_60655("argentum-client", "early/line" + (counter++)); // Identifier.of
			client.method_1531().method_4616(id, new class_1043(image)); // getTextureManager().registerTexture

			return new Line(id, width, height);
		}
	}

	/** Освободить память шрифта (на будущее, если понадобится выгружать). */
	static void dispose() {
		if (fontData != null) {
			MemoryUtil.memFree(fontData);
			fontData = null;
			font = null;
		}
	}
}
