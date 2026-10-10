package com.ganichgg.argentum.client.ui.webp;

import java.io.IOException;

/**
 * Лёгкий декодер WebP без зависимостей: lossless (VP8L), lossy (VP8) и lossy с альфа-каналом (VP8X + ALPH).
 * Анимацию не читает — для иконок и баннеров она не нужна. Результат — пиксели ARGB.
 */
public final class WebP {
	public static final class Image {
		public final int width;
		public final int height;
		public final int[] argb;

		Image(int width, int height, int[] argb) {
			this.width = width;
			this.height = height;
			this.argb = argb;
		}
	}

	private WebP() { }

	/** Похоже ли на WebP: «RIFF» ???? «WEBP». */
	public static boolean isWebP(byte[] d) {
		return d != null && d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
				&& d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
	}

	public static Image decode(byte[] d) throws IOException {
		if (!isWebP(d)) {
			throw new IOException("это не WebP");
		}

		int pos = 12;
		int vp8Off = -1;
		int vp8Len = 0;
		int vp8lOff = -1;
		int vp8lLen = 0;
		int alphOff = -1;
		int alphLen = 0;

		while (pos + 8 <= d.length) {
			String tag = new String(d, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
			int size = (d[pos + 4] & 0xff) | ((d[pos + 5] & 0xff) << 8) | ((d[pos + 6] & 0xff) << 16) | ((d[pos + 7] & 0xff) << 24);
			int body = pos + 8;

			if (size < 0 || body + size > d.length) {
				size = Math.max(0, d.length - body); // обрезанный файл: берём сколько есть
			}

			switch (tag) {
			case "VP8 ":
				vp8Off = body;
				vp8Len = size;
				break;
			case "VP8L":
				vp8lOff = body;
				vp8lLen = size;
				break;
			case "ALPH":
				alphOff = body;
				alphLen = size;
				break;
			case "ANMF":
			case "ANIM":
				throw new IOException("анимированный WebP не поддерживается");
			default:
				break;
			}

			pos = body + size + (size & 1);
		}

		int[] dims = new int[2];

		if (vp8lOff >= 0) {
			int[] argb = Vp8lDecoder.decode(d, vp8lOff, vp8lLen, dims);

			return new Image(dims[0], dims[1], argb);
		}

		if (vp8Off < 0) {
			throw new IOException("в WebP нет данных изображения");
		}

		int[] argb = Vp8Decoder.decode(d, vp8Off, vp8Len, dims);

		if (alphOff >= 0) {
			applyAlpha(d, alphOff, alphLen, dims[0], dims[1], argb);
		}

		return new Image(dims[0], dims[1], argb);
	}

	/** ALPH: заголовок (сжатие, фильтр) и плоскость альфы — сырая или в виде безголового VP8L. */
	private static void applyAlpha(byte[] d, int off, int len, int w, int h, int[] argb) throws IOException {
		if (len < 1) {
			return;
		}

		int header = d[off] & 0xff;
		int compression = header & 3;
		int filter = (header >> 4) & 3;
		byte[] alpha;

		if (compression == 0) {
			alpha = new byte[w * h];
			System.arraycopy(d, off + 1, alpha, 0, Math.min(alpha.length, len - 1));
		} else if (compression == 1) {
			alpha = Vp8lDecoder.decodeAlpha(d, off + 1, len - 1, w, h);
		} else {
			return;
		}

		if (filter != 0) {
			unfilter(alpha, w, h, filter);
		}

		for (int i = 0; i < argb.length; i++) {
			argb[i] = (argb[i] & 0x00ffffff) | ((alpha[i] & 0xff) << 24);
		}
	}

	private static void unfilter(byte[] a, int w, int h, int method) {
		for (int y = 0; y < h; y++) {
			int row = y * w;

			for (int x = 0; x < w; x++) {
				int pred;

				if (y == 0) {
					pred = x == 0 ? 0 : a[row + x - 1] & 0xff;
				} else if (x == 0) {
					pred = a[row - w] & 0xff;
				} else if (method == 1) { // горизонтальный
					pred = a[row + x - 1] & 0xff;
				} else if (method == 2) { // вертикальный
					pred = a[row - w + x] & 0xff;
				} else { // градиентный
					int left = a[row + x - 1] & 0xff;
					int top = a[row - w + x] & 0xff;
					int topLeft = a[row - w + x - 1] & 0xff;
					pred = Math.max(0, Math.min(255, left + top - topLeft));
				}

				a[row + x] = (byte) (a[row + x] + pred);
			}
		}
	}
}
