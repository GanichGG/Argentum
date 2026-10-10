package com.ganichgg.argentum.client.ui;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.class_2960;
import net.minecraft.class_286;
import net.minecraft.class_287;
import net.minecraft.class_289;
import net.minecraft.class_290;
import net.minecraft.class_293;
import net.minecraft.class_332;
import net.minecraft.class_757;

/**
 * Иконка мода как плоскость в пространстве: при наведении она разворачивается к курсору и следит за ним.
 *
 * <p>Это настоящая перспективная проекция: сетка из N×N ячеек поворачивается вокруг двух осей и проецируется на
 * экран, поэтому край, уходящий вдаль, становится меньше (текстура по ячейкам, а не одна аффинная). Тень и боковая
 * освещённость усиливают объём. Масштаб в целом не меняется.
 *
 * <p>class_289 = Tessellator, class_287 = BufferBuilder, class_286 = BufferRenderer, class_290 = VertexFormats
 * (field_1575 = POSITION_TEXTURE_COLOR, field_1576 = POSITION_COLOR), class_293$class_5596.field_27382 = QUADS,
 * class_757 = GameRenderer (method_34543 = positionTexColor, method_34540 = positionColor).
 */
public final class Icon3D {
	private static final int N = 8; // ячеек по стороне
	private static final float CAMERA = 2.4F; // расстояние до камеры в размерах иконки
	private static final float LIGHT_X = -0.35F;
	private static final float LIGHT_Y = -0.5F;
	private static final float LIGHT_Z = 0.79F;

	private Icon3D() { }

	/**
	 * @param ay поворот вокруг вертикальной оси (радианы): больше нуля — правый край уходит вдаль
	 * @param bx поворот вокруг горизонтальной оси: больше нуля — нижний край уходит вдаль
	 */
	public static void draw(class_332 ctx, class_2960 texture, float x, float y, float size, float ay, float bx, float alpha) {
		ctx.method_51452(); // draw(): сначала сбрасываем то, что DrawContext накопил, чтобы порядок слоёв не нарушился

		Matrix4f m = ctx.method_51448().method_23760().method_23761();
		float cx = x + size / 2F;
		float cy = y + size / 2F;
		float ca = (float) Math.cos(ay);
		float sa = (float) Math.sin(ay);
		float cb = (float) Math.cos(bx);
		float sb = (float) Math.sin(bx);
		float camera = CAMERA * size;

		// проекция вершин сетки
		float[] px = new float[(N + 1) * (N + 1)];
		float[] py = new float[(N + 1) * (N + 1)];

		for (int j = 0; j <= N; j++) {
			for (int i = 0; i <= N; i++) {
				float lx = (i / (float) N - 0.5F) * size;
				float ly = (j / (float) N - 0.5F) * size;
				float x1 = lx * ca;
				float z1 = -lx * sa;
				float y2 = ly * cb + z1 * sb;
				float z2 = z1 * cb - ly * sb;
				float f = camera / (camera - z2);
				px[j * (N + 1) + i] = cx + x1 * f;
				py[j * (N + 1) + i] = cy + y2 * f;
			}
		}

		// нормаль плоскости и освещённость: плоская иконка = 1.0, отвёрнутая от света — темнее
		float nx = sa;
		float ny = ca * sb;
		float nz = ca * cb;
		float dot = Math.max(0F, nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z);
		float light = Math.min(1F, 0.72F + 0.28F * (dot / LIGHT_Z));
		int shade = Math.round(light * 255);
		int a = Math.round(alpha * 255);

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		// тень под иконкой: тот же четырёхугольник, смещённый вниз-вправо
		RenderSystem.setShader(class_757::method_34540);
		class_287 shadow = class_289.method_1348().method_60827(class_293.class_5596.field_27382, class_290.field_1576);
		int s = Math.round(alpha * 90);
		int i00 = 0;
		int i10 = N;
		int i11 = (N + 1) * (N + 1) - 1;
		int i01 = N * (N + 1);
		shadow.method_22918(m, px[i00] + 2, py[i00] + 3, 0F).method_1336(0, 0, 0, s);
		shadow.method_22918(m, px[i01] + 2, py[i01] + 3, 0F).method_1336(0, 0, 0, s);
		shadow.method_22918(m, px[i11] + 2, py[i11] + 3, 0F).method_1336(0, 0, 0, s);
		shadow.method_22918(m, px[i10] + 2, py[i10] + 3, 0F).method_1336(0, 0, 0, s);
		class_286.method_43433(shadow.method_60794());

		// сама иконка
		RenderSystem.setShader(class_757::method_34543);
		RenderSystem.setShaderTexture(0, texture);
		class_287 buf = class_289.method_1348().method_60827(class_293.class_5596.field_27382, class_290.field_1575);

		for (int j = 0; j < N; j++) {
			for (int i = 0; i < N; i++) {
				quad(buf, m, px, py, i, j, shade, a);
			}
		}

		class_286.method_43433(buf.method_60794());
	}

	private static void quad(class_287 buf, Matrix4f m, float[] px, float[] py, int i, int j, int shade, int a) {
		vertex(buf, m, px, py, i, j, shade, a);
		vertex(buf, m, px, py, i, j + 1, shade, a);
		vertex(buf, m, px, py, i + 1, j + 1, shade, a);
		vertex(buf, m, px, py, i + 1, j, shade, a);
	}

	private static void vertex(class_287 buf, Matrix4f m, float[] px, float[] py, int i, int j, int shade, int a) {
		int k = j * (N + 1) + i;
		buf.method_22918(m, px[k], py[k], 0F).method_22913(i / (float) N, j / (float) N).method_1336(shade, shade, shade, a);
	}
}
