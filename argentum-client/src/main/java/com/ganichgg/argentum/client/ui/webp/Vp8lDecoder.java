package com.ganichgg.argentum.client.ui.webp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Декодер WebP Lossless (VP8L) по спецификации: префиксные коды с метакартой, цветовой кэш, обратные ссылки и четыре
 * обратных преобразования (предсказание, цветовое, вычитание зелёного, палитра). Результат — пиксели ARGB.
 */
final class Vp8lDecoder {
	private static final int NUM_LITERAL = 256;
	private static final int NUM_LENGTH_CODES = 24;
	private static final int NUM_DISTANCE_CODES = 40;
	private static final int MAX_CODE_LENGTH = 15;

	private final byte[] data;
	private final int end;
	private long bitPos;

	/** Преобразование, прочитанное из потока (применяется в обратном порядке). */
	private static final class Transform {
		int type;
		int bits;
		int[] data;
		int width; // ширина изображения, на которую это преобразование действует
		int height;
	}

	private final List<Transform> transforms = new ArrayList<>();

	private Vp8lDecoder(byte[] data, int start, int end) {
		this.data = data;
		this.end = end;
		this.bitPos = (long) start * 8;
	}

	/** Полный поток VP8L (с заголовком). Возвращает ARGB; размеры кладёт в dims. */
	static int[] decode(byte[] data, int off, int len, int[] dims) throws IOException {
		Vp8lDecoder d = new Vp8lDecoder(data, off, off + len);

		if (d.readBits(8) != 0x2f) {
			throw new IOException("не VP8L");
		}

		int w = d.readBits(14) + 1;
		int h = d.readBits(14) + 1;
		d.readBits(1); // alpha_is_used — на вывод не влияет: альфа берётся из пикселей
		d.readBits(3); // версия

		dims[0] = w;
		dims[1] = h;

		return d.decodeMain(w, h);
	}

	/** Поток альфа-канала из ALPH: VP8L без заголовка, размеры известны; альфа лежит в зелёном канале. */
	static byte[] decodeAlpha(byte[] data, int off, int len, int w, int h) throws IOException {
		Vp8lDecoder d = new Vp8lDecoder(data, off, off + len);
		int[] argb = d.decodeMain(w, h);
		byte[] alpha = new byte[w * h];

		for (int i = 0; i < alpha.length; i++) {
			alpha[i] = (byte) (argb[i] >> 8);
		}

		return alpha;
	}

	// ---------------------------------------------------------------- биты

	private int readBits(int n) throws IOException {
		int value = 0;

		for (int i = 0; i < n; i++) {
			int byteIndex = (int) (bitPos >> 3);

			if (byteIndex >= end) {
				throw new IOException("поток VP8L оборван");
			}

			value |= ((data[byteIndex] >> (int) (bitPos & 7)) & 1) << i;
			bitPos++;
		}

		return value;
	}

	// ---------------------------------------------------------------- коды Хаффмана

	/** Канонический код (как в DEFLATE): биты читаются по одному, старший бит кода идёт первым. */
	private static final class Huffman {
		final int[] count = new int[MAX_CODE_LENGTH + 1];
		int[] symbols;
		int single = -1; // единственный символ: в потоке занимает ноль бит

		static Huffman ofSingle(int symbol) {
			Huffman h = new Huffman();
			h.single = symbol;

			return h;
		}

		static Huffman ofLengths(int[] lengths) throws IOException {
			Huffman h = new Huffman();
			int used = 0;
			int last = 0;

			for (int i = 0; i < lengths.length; i++) {
				if (lengths[i] > 0) {
					h.count[lengths[i]]++;
					used++;
					last = i;
				}
			}

			if (used == 0) {
				throw new IOException("пустой код Хаффмана");
			}

			if (used == 1) {
				return ofSingle(last);
			}

			int[] offsets = new int[MAX_CODE_LENGTH + 2];

			for (int len = 1; len <= MAX_CODE_LENGTH; len++) {
				offsets[len + 1] = offsets[len] + h.count[len];
			}

			h.symbols = new int[used];

			for (int i = 0; i < lengths.length; i++) {
				if (lengths[i] > 0) {
					h.symbols[offsets[lengths[i]]++] = i;
				}
			}

			return h;
		}

		int decode(Vp8lDecoder d) throws IOException {
			if (this.single >= 0) {
				return this.single;
			}

			int code = 0;
			int first = 0;
			int index = 0;

			for (int len = 1; len <= MAX_CODE_LENGTH; len++) {
				code |= d.readBits(1);
				int count = this.count[len];

				if (code - count < first) {
					return this.symbols[index + (code - first)];
				}

				index += count;
				first += count;
				first <<= 1;
				code <<= 1;
			}

			throw new IOException("неверный код Хаффмана");
		}
	}

	private Huffman readCode(int alphabetSize) throws IOException {
		if (readBits(1) == 1) { // простой код: один или два символа
			int numSymbols = readBits(1) + 1;
			int first = readBits(readBits(1) == 1 ? 8 : 1);

			if (numSymbols == 1) {
				return Huffman.ofSingle(first);
			}

			int second = readBits(8);
			int[] lengths = new int[Math.max(alphabetSize, Math.max(first, second) + 1)];
			lengths[first] = 1;
			lengths[second] = 1;

			return Huffman.ofLengths(lengths);
		}

		int numCodeLengthCodes = 4 + readBits(4);
		int[] clLengths = new int[19];

		for (int i = 0; i < numCodeLengthCodes; i++) {
			clLengths[WebPTables.CODE_LENGTH_ORDER[i]] = readBits(3);
		}

		Huffman clCode = Huffman.ofLengths(clLengths);
		int maxSymbol = alphabetSize;

		if (readBits(1) == 1) {
			int lengthNBits = 2 + 2 * readBits(3);
			maxSymbol = 2 + readBits(lengthNBits);
		}

		int[] lengths = new int[alphabetSize];
		int symbol = 0;
		int prev = 8;

		while (symbol < alphabetSize) {
			if (maxSymbol-- == 0) {
				break;
			}

			int codeLen = clCode.decode(this);

			if (codeLen < 16) {
				lengths[symbol++] = codeLen;

				if (codeLen != 0) {
					prev = codeLen;
				}
			} else {
				int extraBits = codeLen == 16 ? 2 : codeLen == 17 ? 3 : 7;
				int base = codeLen == 18 ? 11 : 3;
				int repeat = readBits(extraBits) + base;

				if (symbol + repeat > alphabetSize) {
					throw new IOException("неверная длина повтора");
				}

				int value = codeLen == 16 ? prev : 0;

				for (int i = 0; i < repeat; i++) {
					lengths[symbol++] = value;
				}
			}
		}

		return Huffman.ofLengths(lengths);
	}

	/** Пять кодов: зелёный+длина+кэш, красный, синий, альфа, расстояние. */
	private static final class Group {
		Huffman green;
		Huffman red;
		Huffman blue;
		Huffman alpha;
		Huffman dist;
	}

	private Group readGroup(int cacheBits) throws IOException {
		Group g = new Group();
		g.green = readCode(NUM_LITERAL + NUM_LENGTH_CODES + (cacheBits > 0 ? 1 << cacheBits : 0));
		g.red = readCode(NUM_LITERAL);
		g.blue = readCode(NUM_LITERAL);
		g.alpha = readCode(NUM_LITERAL);
		g.dist = readCode(NUM_DISTANCE_CODES);

		return g;
	}

	// ---------------------------------------------------------------- изображение

	private static int subsample(int size, int bits) {
		return (size + (1 << bits) - 1) >> bits;
	}

	private int[] decodeMain(int width, int height) throws IOException {
		int xsize = width;

		while (readBits(1) == 1) {
			Transform t = new Transform();
			t.type = readBits(2);
			t.width = xsize;
			t.height = height;

			switch (t.type) {
			case 0: // предсказание
			case 1: // цветовое
				t.bits = readBits(3) + 2;
				t.data = decodeStream(subsample(xsize, t.bits), subsample(height, t.bits), false);
				break;
			case 2: // вычитание зелёного
				break;
			default: { // палитра
				int size = readBits(8) + 1;
				int[] table = decodeStream(size, 1, false);

				for (int i = 1; i < size; i++) {
					table[i] = addPixels(table[i], table[i - 1]);
				}

				t.data = new int[256];
				System.arraycopy(table, 0, t.data, 0, size);
				t.bits = size <= 2 ? 3 : size <= 4 ? 2 : size <= 16 ? 1 : 0;
				xsize = subsample(xsize, t.bits);
				break;
			}
			}

			transforms.add(t);
		}

		int[] pixels = decodeStream(xsize, height, true);

		for (int i = transforms.size() - 1; i >= 0; i--) {
			pixels = applyInverse(transforms.get(i), pixels);
		}

		return pixels;
	}

	private int[] decodeStream(int xsize, int ysize, boolean topLevel) throws IOException {
		int cacheBits = 0;

		if (readBits(1) == 1) {
			cacheBits = readBits(4);

			if (cacheBits < 1 || cacheBits > 11) {
				throw new IOException("неверный размер цветового кэша");
			}
		}

		int[] metaImage = null;
		int metaBits = 0;
		int metaWidth = 0;
		int numGroups = 1;

		if (topLevel && readBits(1) == 1) {
			metaBits = readBits(3) + 2;
			metaWidth = subsample(xsize, metaBits);
			metaImage = decodeStream(metaWidth, subsample(ysize, metaBits), false);

			for (int i = 0; i < metaImage.length; i++) {
				metaImage[i] = (metaImage[i] >> 8) & 0xffff;
				numGroups = Math.max(numGroups, metaImage[i] + 1);
			}
		}

		Group[] groups = new Group[numGroups];

		for (int i = 0; i < numGroups; i++) {
			groups[i] = readGroup(cacheBits);
		}

		int total = xsize * ysize;
		int[] out = new int[total];
		int[] cache = cacheBits > 0 ? new int[1 << cacheBits] : null;
		int cached = 0;
		int pos = 0;

		while (pos < total) {
			int x = pos % xsize;
			int y = pos / xsize;
			Group g = groups[metaImage == null ? 0 : metaImage[(y >> metaBits) * metaWidth + (x >> metaBits)]];
			int s = g.green.decode(this);

			if (s < NUM_LITERAL) {
				int r = g.red.decode(this);
				int b = g.blue.decode(this);
				int a = g.alpha.decode(this);
				out[pos++] = (a << 24) | (r << 16) | (s << 8) | b;
			} else if (s < NUM_LITERAL + NUM_LENGTH_CODES) {
				int length = prefixValue(s - NUM_LITERAL);
				int distSymbol = g.dist.decode(this);
				int dist = mapDistance(prefixValue(distSymbol), xsize);

				if (dist > pos || pos + length > total) {
					throw new IOException("неверная обратная ссылка");
				}

				for (int i = 0; i < length; i++, pos++) {
					out[pos] = out[pos - dist];
				}
			} else {
				if (cache == null) {
					throw new IOException("обращение к пустому кэшу");
				}

				out[pos++] = cache[s - NUM_LITERAL - NUM_LENGTH_CODES];
			}

			if (cache != null) {
				while (cached < pos) {
					int argb = out[cached++];
					cache[(int) (((argb * 0x1e35a7bdL) & 0xffffffffL) >>> (32 - cacheBits))] = argb;
				}
			}
		}

		return out;
	}

	private int prefixValue(int prefix) throws IOException {
		if (prefix < 4) {
			return prefix + 1;
		}

		int extraBits = (prefix - 2) >> 1;
		int offset = (2 + (prefix & 1)) << extraBits;

		return offset + readBits(extraBits) + 1;
	}

	private static int mapDistance(int code, int xsize) {
		if (code > 120) {
			return code - 120;
		}

		int v = WebPTables.DISTANCES[code - 1];
		int dist = (8 - (v & 0xf)) + (v >> 4) * xsize;

		return Math.max(dist, 1);
	}

	// ---------------------------------------------------------------- обратные преобразования

	private static int addPixels(int a, int b) {
		int ag = (a & 0xff00ff00) + (b & 0xff00ff00);
		int rb = (a & 0x00ff00ff) + (b & 0x00ff00ff);

		return (ag & 0xff00ff00) | (rb & 0x00ff00ff);
	}

	private static int average2(int a, int b) {
		return (((a ^ b) & 0xfefefefe) >>> 1) + (a & b);
	}

	private static int ch(int argb, int shift) {
		return (argb >>> shift) & 0xff;
	}

	private static int clamp255(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	private static int select(int l, int t, int tl) {
		int pL = 0;
		int pT = 0;

		for (int shift = 0; shift < 32; shift += 8) {
			int vl = ch(l, shift);
			int vt = ch(t, shift);
			int vtl = ch(tl, shift);
			pL += Math.abs(vt - vtl);
			pT += Math.abs(vl - vtl);
		}

		return pL < pT ? l : t;
	}

	private static int clampAddSubtractFull(int a, int b, int c) {
		int out = 0;

		for (int shift = 0; shift < 32; shift += 8) {
			out |= clamp255(ch(a, shift) + ch(b, shift) - ch(c, shift)) << shift;
		}

		return out;
	}

	private static int clampAddSubtractHalf(int a, int b) {
		int out = 0;

		for (int shift = 0; shift < 32; shift += 8) {
			int va = ch(a, shift);
			out |= clamp255(va + (va - ch(b, shift)) / 2) << shift;
		}

		return out;
	}

	private static int predict(int mode, int l, int t, int tr, int tl) {
		switch (mode) {
		case 0: return 0xff000000;
		case 1: return l;
		case 2: return t;
		case 3: return tr;
		case 4: return tl;
		case 5: return average2(average2(l, tr), t);
		case 6: return average2(l, tl);
		case 7: return average2(l, t);
		case 8: return average2(tl, t);
		case 9: return average2(t, tr);
		case 10: return average2(average2(l, tl), average2(t, tr));
		case 11: return select(l, t, tl);
		case 12: return clampAddSubtractFull(l, t, tl);
		case 13: return clampAddSubtractHalf(average2(l, t), tl);
		default: return 0xff000000;
		}
	}

	private static int[] applyInverse(Transform t, int[] in) {
		int w = t.width;
		int h = t.height;

		switch (t.type) {
		case 0: { // предсказание
			int bw = subsample(w, t.bits);

			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int pos = y * w + x;
					int pred;

					if (y == 0) {
						pred = x == 0 ? 0xff000000 : in[pos - 1];
					} else if (x == 0) {
						pred = in[pos - w];
					} else {
						int mode = (t.data[(y >> t.bits) * bw + (x >> t.bits)] >> 8) & 0xf;
						pred = predict(mode, in[pos - 1], in[pos - w], in[pos - w + 1], in[pos - w - 1]);
					}

					in[pos] = addPixels(in[pos], pred);
				}
			}

			return in;
		}
		case 1: { // цветовое преобразование
			int bw = subsample(w, t.bits);

			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int e = t.data[(y >> t.bits) * bw + (x >> t.bits)];
					int greenToRed = (byte) e; // хранится в синем канале
					int greenToBlue = (byte) (e >> 8);
					int redToBlue = (byte) (e >> 16);
					int pos = y * w + x;
					int argb = in[pos];
					int green = (byte) (argb >> 8);
					int red = ch(argb, 16);
					int blue = ch(argb, 0);

					red = (red + ((greenToRed * green) >> 5)) & 0xff;
					blue = (blue + ((greenToBlue * green) >> 5) + ((redToBlue * (byte) red) >> 5)) & 0xff;
					in[pos] = (argb & 0xff00ff00) | (red << 16) | blue;
				}
			}

			return in;
		}
		case 2: // вычитание зелёного
			for (int i = 0; i < in.length; i++) {
				int argb = in[i];
				int green = (argb >> 8) & 0xff;
				int red = (((argb >> 16) & 0xff) + green) & 0xff;
				int blue = ((argb & 0xff) + green) & 0xff;
				in[i] = (argb & 0xff00ff00) | (red << 16) | blue;
			}

			return in;
		default: { // палитра
			int[] out = new int[w * h];
			int packedW = subsample(w, t.bits);

			if (t.bits == 0) {
				for (int i = 0; i < out.length; i++) {
					out[i] = t.data[(in[i] >> 8) & 0xff];
				}
			} else {
				int bitsPerPixel = 8 >> t.bits;
				int mask = (1 << bitsPerPixel) - 1;

				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int packed = (in[y * packedW + (x >> t.bits)] >> 8) & 0xff;
						int index = (packed >> ((x & ((1 << t.bits) - 1)) * bitsPerPixel)) & mask;
						out[y * w + x] = t.data[index];
					}
				}
			}

			return out;
		}
		}
	}
}
