package com.ganichgg.argentum.client.ui.webp;

import java.io.IOException;

/**
 * Декодер ключевого кадра VP8 (RFC 6386) — «потерянный» WebP. Разбор заголовка и режимов, токены коэффициентов,
 * обратные WHT/DCT, внутрикадровые предсказания, петлевой фильтр и перевод YUV в RGB с «красивой» передискретизацией
 * цветности. Результат — пиксели RGB (альфа 255; настоящую альфу добавляет вызывающий из ALPH).
 */
final class Vp8Decoder {
	// режимы предсказания 16×16 и цветности (DC=0, V=1, H=2, TM=3), B_PRED=4; режимы подблоков — по RFC 6386
	private static final int DC_PRED = 0;
	private static final int V_PRED = 1;
	private static final int H_PRED = 2;
	private static final int TM_PRED = 3;
	private static final int B_PRED = 4;

	private static final int[] MB_TO_B_MODE = {0, 2, 3, 1}; // DC→B_DC, V→B_VE, H→B_HE, TM→B_TM

	/** Булев декодер арифметического кодирования VP8. */
	private static final class Bool {
		private final byte[] d;
		private int pos;
		private final int end;
		private int value;
		private int range = 255;
		private int bitCount;

		Bool(byte[] d, int start, int end) {
			this.d = d;
			this.pos = start;
			this.end = end;
			this.value = (next() << 8) | next();
		}

		private int next() {
			return pos < end ? d[pos++] & 0xff : 0;
		}

		int bit(int prob) {
			int split = 1 + (((range - 1) * prob) >> 8);
			int bigSplit = split << 8;
			int result;

			if (value >= bigSplit) {
				result = 1;
				range -= split;
				value -= bigSplit;
			} else {
				result = 0;
				range = split;
			}

			while (range < 128) {
				value <<= 1;
				range <<= 1;

				if (++bitCount == 8) {
					bitCount = 0;
					value |= next();
				}
			}

			return result;
		}

		int literal(int bits) {
			int v = 0;

			for (int i = 0; i < bits; i++) {
				v = (v << 1) | bit(128);
			}

			return v;
		}

		int flag() {
			return bit(128);
		}

		int signed(int bits) {
			int v = literal(bits);

			return flag() == 1 ? -v : v;
		}

		int tree(int[] tree, int[] probs, int probOffset) {
			int i = 0;

			do {
				i = tree[i + bit(probs[probOffset + (i >> 1)])];
			} while (i > 0);

			return -i;
		}
	}

	private byte[] data;
	private int width;
	private int height;
	private int mbW;
	private int mbH;

	// заголовок кадра
	private boolean segmentation;
	private boolean updateMap;
	private boolean absoluteDelta;
	private final int[] segQuant = new int[4];
	private final int[] segFilter = new int[4];
	private final int[] segProbs = {255, 255, 255};
	private boolean simpleFilter;
	private int filterLevel;
	private int sharpness;
	private boolean lfDelta;
	private final int[] refLfDelta = new int[4];
	private final int[] modeLfDelta = new int[4];
	private int numParts;
	private boolean useSkipProb;
	private int skipProb;
	private final int[] coefProbs = new int[1056];

	// квантизаторы по сегментам: [сегмент][y1dc, y1ac, y2dc, y2ac, uvdc, uvac]
	private final int[][] quant = new int[4][6];

	// режимы макроблоков
	private int[] mbYMode;
	private int[] mbUvMode;
	private int[] mbSegment;
	private boolean[] mbSkip;
	private int[] mbBModes; // 16 на макроблок
	private boolean[] mbHasCoeffs;

	// кадр без петлевой фильтрации (с границей слева/сверху для предсказаний)
	private byte[] yPlane;
	private byte[] uPlane;
	private byte[] vPlane;
	private int yStride;
	private int uvStride;

	/** Возвращает ARGB (альфа 255); размеры — в dims. */
	static int[] decode(byte[] data, int off, int len, int[] dims) throws IOException {
		Vp8Decoder d = new Vp8Decoder();

		return d.run(data, off, len, dims);
	}

	private int[] run(byte[] buf, int off, int len, int[] dims) throws IOException {
		this.data = buf;

		if (len < 10) {
			throw new IOException("VP8: слишком короткий поток");
		}

		int tag = (buf[off] & 0xff) | ((buf[off + 1] & 0xff) << 8) | ((buf[off + 2] & 0xff) << 16);
		boolean key = (tag & 1) == 0;
		int firstPartSize = tag >>> 5;

		if (!key) {
			throw new IOException("VP8: не ключевой кадр");
		}

		if ((buf[off + 3] & 0xff) != 0x9d || (buf[off + 4] & 0xff) != 0x01 || (buf[off + 5] & 0xff) != 0x2a) {
			throw new IOException("VP8: нет стартового кода");
		}

		width = ((buf[off + 6] & 0xff) | ((buf[off + 7] & 0xff) << 8)) & 0x3fff;
		height = ((buf[off + 8] & 0xff) | ((buf[off + 9] & 0xff) << 8)) & 0x3fff;
		dims[0] = width;
		dims[1] = height;
		mbW = (width + 15) >> 4;
		mbH = (height + 15) >> 4;

		int firstStart = off + 10;
		int firstEnd = firstStart + firstPartSize;

		if (firstEnd > off + len) {
			throw new IOException("VP8: первая партиция выходит за пределы");
		}

		Bool br = new Bool(buf, firstStart, firstEnd);
		parseHeader(br);

		// токен-партиции
		int sizesStart = firstEnd;
		int partStart = sizesStart + 3 * (numParts - 1);
		Bool[] parts = new Bool[numParts];
		int streamEnd = off + len;

		for (int i = 0; i < numParts; i++) {
			int size;

			if (i < numParts - 1) {
				int p = sizesStart + 3 * i;
				size = (buf[p] & 0xff) | ((buf[p + 1] & 0xff) << 8) | ((buf[p + 2] & 0xff) << 16);
			} else {
				size = Math.max(0, streamEnd - partStart);
			}

			int endPos = Math.min(streamEnd, partStart + size);
			parts[i] = new Bool(buf, Math.min(partStart, streamEnd), endPos);
			partStart += size;
		}

		parseModes(br);
		reconstruct(parts);

		if (filterLevel > 0) {
			loopFilter();
		}

		return toRgb();
	}

	// ---------------------------------------------------------------- заголовок

	private void parseHeader(Bool br) {
		br.flag(); // colorspace
		br.flag(); // clamping type

		segmentation = br.flag() == 1;

		if (segmentation) {
			updateMap = br.flag() == 1;

			if (br.flag() == 1) {
				absoluteDelta = br.flag() == 1;

				for (int i = 0; i < 4; i++) {
					segQuant[i] = br.flag() == 1 ? br.signed(7) : 0;
				}

				for (int i = 0; i < 4; i++) {
					segFilter[i] = br.flag() == 1 ? br.signed(6) : 0;
				}
			}

			if (updateMap) {
				for (int i = 0; i < 3; i++) {
					segProbs[i] = br.flag() == 1 ? br.literal(8) : 255;
				}
			}
		}

		simpleFilter = br.flag() == 1;
		filterLevel = br.literal(6);
		sharpness = br.literal(3);
		lfDelta = br.flag() == 1;

		if (lfDelta && br.flag() == 1) {
			for (int i = 0; i < 4; i++) {
				if (br.flag() == 1) {
					refLfDelta[i] = br.signed(6);
				}
			}

			for (int i = 0; i < 4; i++) {
				if (br.flag() == 1) {
					modeLfDelta[i] = br.signed(6);
				}
			}
		}

		numParts = 1 << br.literal(2);

		// квантизация
		int baseQ = br.literal(7);
		int y1dc = br.flag() == 1 ? br.signed(4) : 0;
		int y2dc = br.flag() == 1 ? br.signed(4) : 0;
		int y2ac = br.flag() == 1 ? br.signed(4) : 0;
		int uvdc = br.flag() == 1 ? br.signed(4) : 0;
		int uvac = br.flag() == 1 ? br.signed(4) : 0;

		for (int s = 0; s < 4; s++) {
			int q = baseQ;

			if (segmentation) {
				q = absoluteDelta ? segQuant[s] : baseQ + segQuant[s];
			}

			quant[s][0] = WebPTables.DC_Q[clip(q + y1dc, 127)];
			quant[s][1] = WebPTables.AC_Q[clip(q, 127)];
			quant[s][2] = WebPTables.DC_Q[clip(q + y2dc, 127)] * 2;
			quant[s][3] = Math.max(8, (WebPTables.AC_Q[clip(q + y2ac, 127)] * 101581) >> 16);
			quant[s][4] = WebPTables.DC_Q[clip(q + uvdc, 117)];
			quant[s][5] = WebPTables.AC_Q[clip(q + uvac, 127)];
		}

		br.flag(); // refresh_entropy_probs

		for (int i = 0; i < 1056; i++) {
			coefProbs[i] = br.bit(WebPTables.COEF_UPDATE_PROBS[i]) == 1 ? br.literal(8) : WebPTables.DEFAULT_COEF_PROBS[i];
		}

		useSkipProb = br.flag() == 1;

		if (useSkipProb) {
			skipProb = br.literal(8);
		}
	}

	private static int clip(int v, int max) {
		return v < 0 ? 0 : v > max ? max : v;
	}

	// ---------------------------------------------------------------- режимы макроблоков

	private void parseModes(Bool br) {
		int count = mbW * mbH;
		mbYMode = new int[count];
		mbUvMode = new int[count];
		mbSegment = new int[count];
		mbSkip = new boolean[count];
		mbBModes = new int[count * 16];
		mbHasCoeffs = new boolean[count];

		int[] above = new int[mbW * 4]; // режимы нижнего ряда подблоков макроблока выше (B_DC_PRED = 0 по умолчанию)

		for (int my = 0; my < mbH; my++) {
			int[] left = new int[4];

			for (int mx = 0; mx < mbW; mx++) {
				int mb = my * mbW + mx;

				if (updateMap) {
					mbSegment[mb] = br.bit(segProbs[0]) == 0 ? br.bit(segProbs[1]) : 2 + br.bit(segProbs[2]);
				}

				if (useSkipProb) {
					mbSkip[mb] = br.bit(skipProb) == 1;
				}

				int ymode = br.tree(WebPTables.KF_YMODE_TREE, WebPTables.KF_YMODE_PROB, 0);
				mbYMode[mb] = ymode;

				if (ymode == B_PRED) {
					for (int by = 0; by < 4; by++) {
						for (int bx = 0; bx < 4; bx++) {
							int a = above[mx * 4 + bx];
							int l = left[by];
							int m = br.tree(WebPTables.BMODE_TREE, WebPTables.KF_BMODE_PROBS, (a * 10 + l) * 9);
							mbBModes[mb * 16 + by * 4 + bx] = m;
							above[mx * 4 + bx] = m;
							left[by] = m;
						}
					}
				} else {
					int implied = MB_TO_B_MODE[ymode];

					for (int i = 0; i < 4; i++) {
						above[mx * 4 + i] = implied;
						left[i] = implied;
					}
				}

				mbUvMode[mb] = br.tree(WebPTables.UV_MODE_TREE, WebPTables.KF_UV_MODE_PROB, 0);
			}
		}
	}

	// ---------------------------------------------------------------- коэффициенты и реконструкция

	private int[] aboveNz; // [mbW][9]: 4 Y, 2 U, 2 V, 1 Y2
	private final int[] leftNz = new int[9];

	private void reconstruct(Bool[] parts) {
		yStride = mbW * 16 + 1;
		uvStride = mbW * 8 + 1;
		yPlane = new byte[yStride * (mbH * 16 + 1)];
		uPlane = new byte[uvStride * (mbH * 8 + 1)];
		vPlane = new byte[uvStride * (mbH * 8 + 1)];

		// граница: сверху 127 (вместе с углом), слева 129
		java.util.Arrays.fill(yPlane, 0, yStride, (byte) 127);
		java.util.Arrays.fill(uPlane, 0, uvStride, (byte) 127);
		java.util.Arrays.fill(vPlane, 0, uvStride, (byte) 127);

		for (int r = 1; r <= mbH * 16; r++) {
			yPlane[r * yStride] = (byte) 129;
		}

		for (int r = 1; r <= mbH * 8; r++) {
			uPlane[r * uvStride] = (byte) 129;
			vPlane[r * uvStride] = (byte) 129;
		}

		aboveNz = new int[mbW * 9];
		int[] coeffs = new int[25 * 16];

		for (int my = 0; my < mbH; my++) {
			java.util.Arrays.fill(leftNz, 0);
			Bool tokens = parts[my & (numParts - 1)];

			for (int mx = 0; mx < mbW; mx++) {
				int mb = my * mbW + mx;
				java.util.Arrays.fill(coeffs, 0);
				boolean hasY2 = mbYMode[mb] != B_PRED;
				boolean nonZero = false;

				if (!mbSkip[mb]) {
					nonZero = parseResiduals(tokens, mb, mx, hasY2, coeffs);
				} else {
					for (int i = 0; i < 8; i++) {
						leftNz[i] = 0;
						aboveNz[mx * 9 + i] = 0;
					}

					if (hasY2) {
						leftNz[8] = 0;
						aboveNz[mx * 9 + 8] = 0;
					}
				}

				mbHasCoeffs[mb] = nonZero;
				predictAndAdd(mb, mx, my, coeffs);
			}
		}
	}

	private boolean parseResiduals(Bool br, int mb, int mx, boolean hasY2, int[] coeffs) {
		int[] q = quant[mbSegment[mb]];
		int base = mx * 9;
		boolean any = false;
		int first;
		int yType;

		if (hasY2) {
			int ctx = leftNz[8] + aboveNz[base + 8];
			int[] y2 = new int[16];
			int nz = readCoeffs(br, 1, ctx, q[2], q[3], 0, y2, 0);
			leftNz[8] = aboveNz[base + 8] = nz > 0 ? 1 : 0;
			any |= nz > 0;

			if (nz > 1) {
				inverseWht(y2, coeffs);
			} else {
				int dc0 = (y2[0] + 3) >> 3;

				for (int i = 0; i < 16; i++) {
					coeffs[i * 16] = dc0;
				}
			}

			first = 1;
			yType = 0;
		} else {
			first = 0;
			yType = 3;
		}

		for (int y = 0; y < 4; y++) {
			int l = leftNz[y];

			for (int x = 0; x < 4; x++) {
				int ctx = l + aboveNz[base + x];
				int nz = readCoeffs(br, yType, ctx, q[0], q[1], first, coeffs, (y * 4 + x) * 16);
				l = nz > first ? 1 : 0;
				aboveNz[base + x] = l;
				any |= nz > first;
			}

			leftNz[y] = l;
		}

		for (int plane = 0; plane < 2; plane++) {
			int nzBase = 4 + plane * 2;

			for (int y = 0; y < 2; y++) {
				int l = leftNz[nzBase + y];

				for (int x = 0; x < 2; x++) {
					int ctx = l + aboveNz[base + nzBase + x];
					int nz = readCoeffs(br, 2, ctx, q[4], q[5], 0, coeffs, (16 + plane * 4 + y * 2 + x) * 16);
					l = nz > 0 ? 1 : 0;
					aboveNz[base + nzBase + x] = l;
					any |= nz > 0;
				}

				leftNz[nzBase + y] = l;
			}
		}

		if (!any && hasY2) {
			// все блоки пусты, но DC мог прийти из Y2
			for (int i = 0; i < 16; i++) {
				if (coeffs[i * 16] != 0) {
					return true;
				}
			}
		}

		return any;
	}

	/** Читает токены одного блока; возвращает позицию после последнего ненулевого коэффициента. */
	private int readCoeffs(Bool br, int type, int ctx, int dcQ, int acQ, int first, int[] out, int outOff) {
		int n = first;
		int pBase = ((type * 8 + WebPTables.BANDS[n]) * 3 + ctx) * 11;

		while (n < 16) {
			if (br.bit(coefProbs[pBase]) == 0) {
				return n; // конец блока
			}

			while (br.bit(coefProbs[pBase + 1]) == 0) { // серия нулей
				n++;

				if (n == 16) {
					return 16;
				}

				pBase = ((type * 8 + WebPTables.BANDS[n]) * 3) * 11;
			}

			int v;
			int nextCtx;

			if (br.bit(coefProbs[pBase + 2]) == 0) {
				v = 1;
				nextCtx = 1;
			} else {
				v = largeValue(br, pBase);
				nextCtx = 2;
			}

			int sign = br.bit(128);
			int value = sign == 1 ? -v : v;
			out[outOff + WebPTables.ZIGZAG[n]] = value * (n > 0 ? acQ : dcQ);
			n++;
			pBase = ((type * 8 + WebPTables.BANDS[n]) * 3 + nextCtx) * 11;
		}

		return 16;
	}

	private int largeValue(Bool br, int p) {
		int v;

		if (br.bit(coefProbs[p + 3]) == 0) {
			if (br.bit(coefProbs[p + 4]) == 0) {
				v = 2;
			} else {
				v = 3 + br.bit(coefProbs[p + 5]);
			}
		} else {
			if (br.bit(coefProbs[p + 6]) == 0) {
				if (br.bit(coefProbs[p + 7]) == 0) {
					v = 5 + br.bit(159);
				} else {
					v = 7 + 2 * br.bit(165);
					v += br.bit(145);
				}
			} else {
				int bit1 = br.bit(coefProbs[p + 8]);
				int bit0 = br.bit(coefProbs[p + 9 + bit1]);
				int cat = 2 * bit1 + bit0;
				int[] tab = WebPTables.CAT3456[cat];
				v = 0;

				for (int prob : tab) {
					v += v + br.bit(prob);
				}

				v += 3 + (8 << cat);
			}
		}

		return v;
	}

	/** Обратное преобразование Уолша — Адамара: раздаёт DC шестнадцати блокам яркости. */
	private static void inverseWht(int[] in, int[] out) {
		int[] tmp = new int[16];

		for (int i = 0; i < 4; i++) {
			int a0 = in[i] + in[12 + i];
			int a1 = in[4 + i] + in[8 + i];
			int a2 = in[4 + i] - in[8 + i];
			int a3 = in[i] - in[12 + i];
			tmp[i] = a0 + a1;
			tmp[8 + i] = a0 - a1;
			tmp[4 + i] = a3 + a2;
			tmp[12 + i] = a3 - a2;
		}

		for (int i = 0; i < 4; i++) {
			int dc = tmp[i * 4] + 3;
			int a0 = dc + tmp[3 + i * 4];
			int a1 = tmp[1 + i * 4] + tmp[2 + i * 4];
			int a2 = tmp[1 + i * 4] - tmp[2 + i * 4];
			int a3 = dc - tmp[3 + i * 4];
			out[(i * 4) * 16] = (a0 + a1) >> 3;
			out[(i * 4 + 1) * 16] = (a3 + a2) >> 3;
			out[(i * 4 + 2) * 16] = (a0 - a1) >> 3;
			out[(i * 4 + 3) * 16] = (a3 - a2) >> 3;
		}
	}

	private static int mul1(int a) {
		return ((a * 20091) >> 16) + a;
	}

	private static int mul2(int a) {
		return (a * 35468) >> 16;
	}

	private static int clip8(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	/** Обратное DCT 4×4 с добавлением к предсказанию. */
	private static void idctAdd(int[] in, int inOff, byte[] dst, int dstOff, int stride) {
		int[] tmp = new int[16];

		for (int i = 0; i < 4; i++) {
			int a = in[inOff + i] + in[inOff + 8 + i];
			int b = in[inOff + i] - in[inOff + 8 + i];
			int c = mul2(in[inOff + 4 + i]) - mul1(in[inOff + 12 + i]);
			int d = mul1(in[inOff + 4 + i]) + mul2(in[inOff + 12 + i]);
			tmp[i * 4] = a + d;
			tmp[i * 4 + 1] = b + c;
			tmp[i * 4 + 2] = b - c;
			tmp[i * 4 + 3] = a - d;
		}

		for (int i = 0; i < 4; i++) {
			int dc = tmp[i] + 4;
			int a = dc + tmp[8 + i];
			int b = dc - tmp[8 + i];
			int c = mul2(tmp[4 + i]) - mul1(tmp[12 + i]);
			int d = mul1(tmp[4 + i]) + mul2(tmp[12 + i]);
			int row = dstOff + i * stride;
			dst[row] = (byte) clip8((dst[row] & 0xff) + ((a + d) >> 3));
			dst[row + 1] = (byte) clip8((dst[row + 1] & 0xff) + ((b + c) >> 3));
			dst[row + 2] = (byte) clip8((dst[row + 2] & 0xff) + ((b - c) >> 3));
			dst[row + 3] = (byte) clip8((dst[row + 3] & 0xff) + ((a - d) >> 3));
		}
	}

	private static boolean anyNonZero(int[] c, int off) {
		for (int i = 0; i < 16; i++) {
			if (c[off + i] != 0) {
				return true;
			}
		}

		return false;
	}

	private void predictAndAdd(int mb, int mx, int my, int[] coeffs) {
		int y0 = my * 16 + 1; // индексы с учётом границы
		int x0 = mx * 16 + 1;
		int yBase = y0 * yStride + x0;
		int ymode = mbYMode[mb];

		if (ymode != B_PRED) {
			predict16(yPlane, yStride, yBase, ymode, mx > 0, my > 0, 16);

			for (int b = 0; b < 16; b++) {
				if (anyNonZero(coeffs, b * 16)) {
					idctAdd(coeffs, b * 16, yPlane, yBase + (b >> 2) * 4 * yStride + (b & 3) * 4, yStride);
				}
			}
		} else {
			// верхний правый угол макроблока: пиксели ряда над макроблоком справа (или копия/127)
			int[] topRight = new int[4];

			if (my == 0) {
				java.util.Arrays.fill(topRight, 127);
			} else if (mx == mbW - 1) {
				java.util.Arrays.fill(topRight, yPlane[yBase - yStride + 15] & 0xff);
			} else {
				for (int i = 0; i < 4; i++) {
					topRight[i] = yPlane[yBase - yStride + 16 + i] & 0xff;
				}
			}

			for (int b = 0; b < 16; b++) {
				int bx = b & 3;
				int by = b >> 2;
				int pos = yBase + by * 4 * yStride + bx * 4;
				predict4(yPlane, yStride, pos, mbBModes[mb * 16 + b], bx == 3 ? topRight : null);

				if (anyNonZero(coeffs, b * 16)) {
					idctAdd(coeffs, b * 16, yPlane, pos, yStride);
				}
			}
		}

		int cy0 = my * 8 + 1;
		int cx0 = mx * 8 + 1;
		int cBase = cy0 * uvStride + cx0;
		int uvmode = mbUvMode[mb];
		predict16(uPlane, uvStride, cBase, uvmode, mx > 0, my > 0, 8);
		predict16(vPlane, uvStride, cBase, uvmode, mx > 0, my > 0, 8);

		for (int b = 0; b < 4; b++) {
			int off = cBase + (b >> 1) * 4 * uvStride + (b & 1) * 4;

			if (anyNonZero(coeffs, (16 + b) * 16)) {
				idctAdd(coeffs, (16 + b) * 16, uPlane, off, uvStride);
			}

			if (anyNonZero(coeffs, (20 + b) * 16)) {
				idctAdd(coeffs, (20 + b) * 16, vPlane, off, uvStride);
			}
		}
	}

	/** Предсказание блока size×size (16 для яркости, 8 для цветности). */
	private static void predict16(byte[] p, int stride, int base, int mode, boolean hasLeft, boolean hasTop, int size) {
		switch (mode) {
		case DC_PRED: {
			int sum = 0;
			int dc;
			int shift = size == 16 ? 4 : 3;

			if (hasTop && hasLeft) {
				for (int i = 0; i < size; i++) {
					sum += (p[base - stride + i] & 0xff) + (p[base + i * stride - 1] & 0xff);
				}

				dc = (sum + size) >> (shift + 1);
			} else if (hasTop) {
				for (int i = 0; i < size; i++) {
					sum += p[base - stride + i] & 0xff;
				}

				dc = (sum + (size >> 1)) >> shift;
			} else if (hasLeft) {
				for (int i = 0; i < size; i++) {
					sum += p[base + i * stride - 1] & 0xff;
				}

				dc = (sum + (size >> 1)) >> shift;
			} else {
				dc = 128;
			}

			for (int y = 0; y < size; y++) {
				java.util.Arrays.fill(p, base + y * stride, base + y * stride + size, (byte) dc);
			}

			break;
		}
		case V_PRED:
			for (int y = 0; y < size; y++) {
				System.arraycopy(p, base - stride, p, base + y * stride, size);
			}

			break;
		case H_PRED:
			for (int y = 0; y < size; y++) {
				java.util.Arrays.fill(p, base + y * stride, base + y * stride + size, p[base + y * stride - 1]);
			}

			break;
		default: { // TM_PRED
			int corner = p[base - stride - 1] & 0xff;

			for (int y = 0; y < size; y++) {
				int left = p[base + y * stride - 1] & 0xff;

				for (int x = 0; x < size; x++) {
					p[base + y * stride + x] = (byte) clip8(left + (p[base - stride + x] & 0xff) - corner);
				}
			}
		}
		}
	}

	private static int avg3(int a, int b, int c) {
		return (a + 2 * b + c + 2) >> 2;
	}

	private static int avg2(int a, int b) {
		return (a + b + 1) >> 1;
	}

	/** Предсказание подблока 4×4; topRight — подмена четырёх пикселей справа сверху (для правого столбца подблоков). */
	private static void predict4(byte[] p, int stride, int pos, int mode, int[] topRight) {
		int[] t = new int[8];

		for (int i = 0; i < 4; i++) {
			t[i] = p[pos - stride + i] & 0xff;
		}

		for (int i = 0; i < 4; i++) {
			t[4 + i] = topRight != null ? topRight[i] : p[pos - stride + 4 + i] & 0xff;
		}

		int tl = p[pos - stride - 1] & 0xff;
		int l0 = p[pos - 1] & 0xff;
		int l1 = p[pos + stride - 1] & 0xff;
		int l2 = p[pos + 2 * stride - 1] & 0xff;
		int l3 = p[pos + 3 * stride - 1] & 0xff;
		int[][] o = new int[4][4]; // [y][x]

		switch (mode) {
		case 0: { // B_DC_PRED
			int sum = 4;

			for (int i = 0; i < 4; i++) {
				sum += t[i];
			}

			sum += l0 + l1 + l2 + l3;
			int dc = sum >> 3;

			for (int y = 0; y < 4; y++) {
				for (int x = 0; x < 4; x++) {
					o[y][x] = dc;
				}
			}

			break;
		}
		case 1: { // B_TM_PRED
			int[] l = {l0, l1, l2, l3};

			for (int y = 0; y < 4; y++) {
				for (int x = 0; x < 4; x++) {
					o[y][x] = clip8(l[y] + t[x] - tl);
				}
			}

			break;
		}
		case 2: { // B_VE_PRED
			int[] row = {avg3(tl, t[0], t[1]), avg3(t[0], t[1], t[2]), avg3(t[1], t[2], t[3]), avg3(t[2], t[3], t[4])};

			for (int y = 0; y < 4; y++) {
				System.arraycopy(row, 0, o[y], 0, 4);
			}

			break;
		}
		case 3: { // B_HE_PRED
			int[] col = {avg3(tl, l0, l1), avg3(l0, l1, l2), avg3(l1, l2, l3), avg3(l2, l3, l3)};

			for (int y = 0; y < 4; y++) {
				for (int x = 0; x < 4; x++) {
					o[y][x] = col[y];
				}
			}

			break;
		}
		case 4: { // B_LD_PRED
			int a = t[0];
			int b = t[1];
			int c = t[2];
			int d = t[3];
			int e = t[4];
			int f = t[5];
			int g = t[6];
			int h = t[7];
			o[0][0] = avg3(a, b, c);
			o[0][1] = o[1][0] = avg3(b, c, d);
			o[0][2] = o[1][1] = o[2][0] = avg3(c, d, e);
			o[0][3] = o[1][2] = o[2][1] = o[3][0] = avg3(d, e, f);
			o[1][3] = o[2][2] = o[3][1] = avg3(e, f, g);
			o[2][3] = o[3][2] = avg3(f, g, h);
			o[3][3] = avg3(g, h, h);
			break;
		}
		case 5: { // B_RD_PRED (i,j,k,l — левые, x — угол, a..d — верхние)
			int i = l0;
			int j = l1;
			int k = l2;
			int l = l3;
			int x = tl;
			int a = t[0];
			int b = t[1];
			int c = t[2];
			int d = t[3];
			o[3][0] = avg3(j, k, l);
			o[3][1] = o[2][0] = avg3(i, j, k);
			o[3][2] = o[2][1] = o[1][0] = avg3(x, i, j);
			o[3][3] = o[2][2] = o[1][1] = o[0][0] = avg3(a, x, i);
			o[2][3] = o[1][2] = o[0][1] = avg3(b, a, x);
			o[1][3] = o[0][2] = avg3(c, b, a);
			o[0][3] = avg3(d, c, b);
			break;
		}
		case 6: { // B_VR_PRED
			int i = l0;
			int j = l1;
			int k = l2;
			int x = tl;
			int a = t[0];
			int b = t[1];
			int c = t[2];
			int d = t[3];
			o[3][0] = avg3(k, j, i);
			o[2][0] = avg3(j, i, x);
			o[3][1] = o[1][0] = avg3(i, x, a);
			o[2][1] = o[0][0] = avg2(x, a);
			o[3][2] = o[1][1] = avg3(x, a, b);
			o[2][2] = o[0][1] = avg2(a, b);
			o[3][3] = o[1][2] = avg3(a, b, c);
			o[2][3] = o[0][2] = avg2(b, c);
			o[1][3] = avg3(b, c, d);
			o[0][3] = avg2(c, d);
			break;
		}
		case 7: { // B_VL_PRED
			int a = t[0];
			int b = t[1];
			int c = t[2];
			int d = t[3];
			int e = t[4];
			int f = t[5];
			int g = t[6];
			int h = t[7];
			o[0][0] = avg2(a, b);
			o[1][0] = avg3(a, b, c);
			o[2][0] = o[0][1] = avg2(b, c);
			o[1][1] = o[3][0] = avg3(b, c, d);
			o[2][1] = o[0][2] = avg2(c, d);
			o[3][1] = o[1][2] = avg3(c, d, e);
			o[0][3] = o[2][2] = avg2(d, e);
			o[1][3] = o[3][2] = avg3(d, e, f);
			o[2][3] = avg3(e, f, g);
			o[3][3] = avg3(f, g, h);
			break;
		}
		case 8: { // B_HD_PRED
			int i = l0;
			int j = l1;
			int k = l2;
			int l = l3;
			int x = tl;
			int a = t[0];
			int b = t[1];
			int c = t[2];
			o[3][0] = avg2(k, l);
			o[3][1] = avg3(j, k, l);
			o[2][0] = o[3][2] = avg2(j, k);
			o[2][1] = o[3][3] = avg3(i, j, k);
			o[2][2] = o[1][0] = avg2(i, j);
			o[2][3] = o[1][1] = avg3(x, i, j);
			o[1][2] = o[0][0] = avg2(x, i);
			o[1][3] = o[0][1] = avg3(a, x, i);
			o[0][2] = avg3(b, a, x);
			o[0][3] = avg3(c, b, a);
			break;
		}
		default: { // B_HU_PRED
			int i = l0;
			int j = l1;
			int k = l2;
			int l = l3;
			o[0][0] = avg2(i, j);
			o[0][1] = avg3(i, j, k);
			o[0][2] = o[1][0] = avg2(j, k);
			o[0][3] = o[1][1] = avg3(j, k, l);
			o[1][2] = o[2][0] = avg2(k, l);
			o[1][3] = o[2][1] = avg3(k, l, l);
			o[2][2] = o[2][3] = o[3][0] = o[3][1] = o[3][2] = o[3][3] = l;
		}
		}

		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				p[pos + y * stride + x] = (byte) o[y][x];
			}
		}
	}

	// ---------------------------------------------------------------- петлевой фильтр

	private void loopFilter() {
		for (int my = 0; my < mbH; my++) {
			for (int mx = 0; mx < mbW; mx++) {
				int mb = my * mbW + mx;
				boolean i4x4 = mbYMode[mb] == B_PRED;
				int level = filterLevel;

				if (segmentation) {
					level = absoluteDelta ? segFilter[mbSegment[mb]] : filterLevel + segFilter[mbSegment[mb]];
				}

				if (lfDelta) {
					level += refLfDelta[0];

					if (i4x4) {
						level += modeLfDelta[0];
					}
				}

				level = level < 0 ? 0 : level > 63 ? 63 : level;

				if (level == 0) {
					continue;
				}

				int ilevel = level;

				if (sharpness > 0) {
					ilevel >>= sharpness > 4 ? 2 : 1;

					if (ilevel > 9 - sharpness) {
						ilevel = 9 - sharpness;
					}
				}

				if (ilevel < 1) {
					ilevel = 1;
				}

				int limit = 2 * level + ilevel;
				int hev = level >= 40 ? 2 : level >= 15 ? 1 : 0;
				boolean inner = mbHasCoeffs[mb] || i4x4;
				int yPos = (my * 16 + 1) * yStride + mx * 16 + 1;
				int cPos = (my * 8 + 1) * uvStride + mx * 8 + 1;

				if (simpleFilter) {
					if (mx > 0) {
						simpleEdge(yPlane, yPos, 1, yStride, 16, limit + 4);
					}

					if (inner) {
						for (int i = 4; i < 16; i += 4) {
							simpleEdge(yPlane, yPos + i, 1, yStride, 16, limit);
						}
					}

					if (my > 0) {
						simpleEdge(yPlane, yPos, yStride, 1, 16, limit + 4);
					}

					if (inner) {
						for (int i = 4; i < 16; i += 4) {
							simpleEdge(yPlane, yPos + i * yStride, yStride, 1, 16, limit);
						}
					}

					continue;
				}

				if (mx > 0) {
					normalEdge(yPlane, yPos, 1, yStride, 16, limit + 4, ilevel, hev, true);
					normalEdge(uPlane, cPos, 1, uvStride, 8, limit + 4, ilevel, hev, true);
					normalEdge(vPlane, cPos, 1, uvStride, 8, limit + 4, ilevel, hev, true);
				}

				if (inner) {
					for (int i = 4; i < 16; i += 4) {
						normalEdge(yPlane, yPos + i, 1, yStride, 16, limit, ilevel, hev, false);
					}

					normalEdge(uPlane, cPos + 4, 1, uvStride, 8, limit, ilevel, hev, false);
					normalEdge(vPlane, cPos + 4, 1, uvStride, 8, limit, ilevel, hev, false);
				}

				if (my > 0) {
					normalEdge(yPlane, yPos, yStride, 1, 16, limit + 4, ilevel, hev, true);
					normalEdge(uPlane, cPos, uvStride, 1, 8, limit + 4, ilevel, hev, true);
					normalEdge(vPlane, cPos, uvStride, 1, 8, limit + 4, ilevel, hev, true);
				}

				if (inner) {
					for (int i = 4; i < 16; i += 4) {
						normalEdge(yPlane, yPos + i * yStride, yStride, 1, 16, limit, ilevel, hev, false);
					}

					normalEdge(uPlane, cPos + 4 * uvStride, uvStride, 1, 8, limit, ilevel, hev, false);
					normalEdge(vPlane, cPos + 4 * uvStride, uvStride, 1, 8, limit, ilevel, hev, false);
				}
			}
		}
	}

	private static int sc1(int v) {
		return v < -128 ? -128 : v > 127 ? 127 : v;
	}

	private static int sc2(int v) {
		return v < -16 ? -16 : v > 15 ? 15 : v;
	}

	/** Простой фильтр вдоль края: step — шаг поперёк края, along — шаг вдоль края. */
	private static void simpleEdge(byte[] p, int pos, int step, int along, int count, int thresh) {
		int t2 = 2 * thresh + 1;

		for (int i = 0; i < count; i++, pos += along) {
			int p1 = p[pos - 2 * step] & 0xff;
			int p0 = p[pos - step] & 0xff;
			int q0 = p[pos] & 0xff;
			int q1 = p[pos + step] & 0xff;

			if (4 * Math.abs(p0 - q0) + Math.abs(p1 - q1) <= t2) {
				filter2(p, pos, step, p1, p0, q0, q1);
			}
		}
	}

	private static void filter2(byte[] p, int pos, int step, int p1, int p0, int q0, int q1) {
		int a = 3 * (q0 - p0) + sc1(p1 - q1);
		int a1 = sc2((a + 4) >> 3);
		int a2 = sc2((a + 3) >> 3);
		p[pos - step] = (byte) clip8(p0 + a2);
		p[pos] = (byte) clip8(q0 - a1);
	}

	private static void normalEdge(byte[] p, int pos, int step, int along, int count, int thresh, int ithresh, int hevThresh, boolean mbEdge) {
		int t2 = 2 * thresh + 1;

		for (int i = 0; i < count; i++, pos += along) {
			int p3 = p[pos - 4 * step] & 0xff;
			int p2 = p[pos - 3 * step] & 0xff;
			int p1 = p[pos - 2 * step] & 0xff;
			int p0 = p[pos - step] & 0xff;
			int q0 = p[pos] & 0xff;
			int q1 = p[pos + step] & 0xff;
			int q2 = p[pos + 2 * step] & 0xff;
			int q3 = p[pos + 3 * step] & 0xff;

			if (4 * Math.abs(p0 - q0) + Math.abs(p1 - q1) > t2) {
				continue;
			}

			if (Math.abs(p3 - p2) > ithresh || Math.abs(p2 - p1) > ithresh || Math.abs(p1 - p0) > ithresh
					|| Math.abs(q3 - q2) > ithresh || Math.abs(q2 - q1) > ithresh || Math.abs(q1 - q0) > ithresh) {
				continue;
			}

			boolean hev = Math.abs(p1 - p0) > hevThresh || Math.abs(q1 - q0) > hevThresh;

			if (hev) {
				filter2(p, pos, step, p1, p0, q0, q1);
			} else if (mbEdge) {
				int a = sc1(3 * (q0 - p0) + sc1(p1 - q1));
				int a1 = (27 * a + 63) >> 7;
				int a2 = (18 * a + 63) >> 7;
				int a3 = (9 * a + 63) >> 7;
				p[pos - 3 * step] = (byte) clip8(p2 + a3);
				p[pos - 2 * step] = (byte) clip8(p1 + a2);
				p[pos - step] = (byte) clip8(p0 + a1);
				p[pos] = (byte) clip8(q0 - a1);
				p[pos + step] = (byte) clip8(q1 - a2);
				p[pos + 2 * step] = (byte) clip8(q2 - a3);
			} else {
				int a = 3 * (q0 - p0);
				int a1 = sc2((a + 4) >> 3);
				int a2 = sc2((a + 3) >> 3);
				int a3 = (a1 + 1) >> 1;
				p[pos - 2 * step] = (byte) clip8(p1 + a3);
				p[pos - step] = (byte) clip8(p0 + a2);
				p[pos] = (byte) clip8(q0 - a1);
				p[pos + step] = (byte) clip8(q1 - a3);
			}
		}
	}

	// ---------------------------------------------------------------- YUV → RGB

	private static int mulHi(int v, int coeff) {
		return (v * coeff) >> 8;
	}

	private static int clip8Fix(int v) {
		return (v & ~16383) == 0 ? v >> 6 : v < 0 ? 0 : 255;
	}

	private static int yuv(int y, int u, int v) {
		int r = clip8Fix(mulHi(y, 19077) + mulHi(v, 26149) - 14234);
		int g = clip8Fix(mulHi(y, 19077) - mulHi(u, 6419) - mulHi(v, 13320) + 8708);
		int b = clip8Fix(mulHi(y, 19077) + mulHi(u, 33050) - 17685);

		return 0xff000000 | (r << 16) | (g << 8) | b;
	}

	private int[] toRgb() {
		int[] out = new int[width * height];
		int cw = (width + 1) >> 1;
		int ch = (height + 1) >> 1;

		for (int y = 0; y < height; y++) {
			// строка y использует цветность из пары строк: (верхняя, нижняя) = (k-1, k) для нечётной y=2k-1 и чётной y=2k
			int topRow;
			int curRow;
			boolean bottom;

			if (y == 0) {
				topRow = 0;
				curRow = 0;
				bottom = false;
			} else if ((y & 1) == 1) {
				int k = (y + 1) >> 1;
				topRow = k - 1;
				curRow = Math.min(k, ch - 1);
				bottom = false;
			} else {
				int k = y >> 1;
				topRow = k - 1;
				curRow = k;
				bottom = true;
			}

			for (int x = 0; x < width; x++) {
				int yy = yPlane[(y + 1) * yStride + x + 1] & 0xff;
				int cx = x >> 1;
				int u;
				int v;

				// горизонтальное положение внутри пары: чётный x — «правый» пиксель пары слева, нечётный — «левый»
				int xl;
				int xr;
				boolean first = x == 0;
				boolean lastOdd = x == width - 1 && (width & 1) == 0;

				if (first || lastOdd) {
					int c = first ? 0 : cw - 1;
					int tl = chroma(uPlane, topRow, c);
					int l = chroma(uPlane, curRow, c);
					u = bottom ? (3 * l + tl + 2) >> 2 : (3 * tl + l + 2) >> 2;
					tl = chroma(vPlane, topRow, c);
					l = chroma(vPlane, curRow, c);
					v = bottom ? (3 * l + tl + 2) >> 2 : (3 * tl + l + 2) >> 2;
				} else {
					// пиксель x принадлежит паре между колонками cx-1 и cx (x нечётный → 2*cx-1 .. 2*cx при cx=(x+1)/2)
					int pairX = (x + 1) >> 1; // индекс колонки «x» в формуле
					boolean oddPixel = (x & 1) == 1; // пиксель 2*pairX-1
					int[] uv = new int[2];

					for (int c = 0; c < 2; c++) {
						byte[] plane = c == 0 ? uPlane : vPlane;
						int tl = chroma(plane, topRow, pairX - 1);
						int t = chroma(plane, topRow, pairX);
						int l = chroma(plane, curRow, pairX - 1);
						int uu = chroma(plane, curRow, pairX);
						int avg = tl + t + l + uu + 8;
						int diag12 = (avg + 2 * (t + l)) >> 3;
						int diag03 = (avg + 2 * (tl + uu)) >> 3;

						if (!bottom) {
							uv[c] = oddPixel ? (diag12 + tl) >> 1 : (diag03 + t) >> 1;
						} else {
							uv[c] = oddPixel ? (diag03 + l) >> 1 : (diag12 + uu) >> 1;
						}
					}

					u = uv[0];
					v = uv[1];
				}

				out[y * width + x] = yuv(yy, u, v);
			}
		}

		return out;
	}

	private int chroma(byte[] plane, int row, int col) {
		return plane[(row + 1) * uvStride + col + 1] & 0xff;
	}
}
