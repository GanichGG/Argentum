package com.ganichgg.argentum.client.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.ganichgg.argentum.client.ui.webp.WebP;

import net.fabricmc.loader.impl.modrinth.ModrinthCache;
import net.fabricmc.loader.impl.modrinth.ModrinthProject;
import net.minecraft.class_1011;
import net.minecraft.class_1043;
import net.minecraft.class_2960;
import net.minecraft.class_310;

/**
 * Иконки модов: качаются и декодируются в фоне, а текстуры создаются в потоке рендера (там нужен GL).
 * Декодер — STB (PNG/JPEG/GIF/BMP). У многих проектов в API лежит webp, который STB не читает, поэтому
 * для них пробуем {@code cdn.modrinth.com/data/<id>/icon.png}; если и так не вышло — остаётся плейсхолдер.
 *
 * <p>class_1011 = NativeImage, class_1043 = NativeImageBackedTexture, class_2960 = Identifier, class_310 = MinecraftClient.
 */
public final class IconCache {
	private static final int MAX_DOWNLOAD = 3 * 1024 * 1024;
	private static final int MAX_SIDE = 128; // больше не нужно: в карточке иконка ~40 единиц GUI
	private static final Pattern CDN_ID = Pattern.compile("^https://cdn\\.modrinth\\.com/data/([^/]+)/");

	private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "argentum-icons");
		t.setDaemon(true);
		return t;
	});

	/** Состояние одной иконки; поля пишет то фоновый поток, то рендер — отсюда volatile. */
	public static final class Icon {
		public volatile class_2960 texture;
		public volatile int width;
		public volatile int height;
		public volatile long shownAtNanos;
		volatile boolean failed;
	}

	private static final class Decoded {
		final Icon icon;
		final int width;
		final int height;
		final int[] abgr;

		Decoded(Icon icon, int width, int height, int[] abgr) {
			this.icon = icon;
			this.width = width;
			this.height = height;
			this.abgr = abgr;
		}
	}

	private static final long ICON_TTL_MS = 7L * 24 * 60 * 60 * 1000; // неделя

	private final ModrinthCache cache;
	private final Map<String, Icon> icons = new ConcurrentHashMap<>();
	private final Queue<Decoded> ready = new ConcurrentLinkedQueue<>();
	private int counter;

	public IconCache(ModrinthCache cache) {
		this.cache = cache;
	}

	public Icon get(ModrinthProject project) {
		return icons.computeIfAbsent(project.id, id -> {
			Icon icon = new Icon();
			POOL.submit(() -> load(project, icon));

			return icon;
		});
	}

	/** Раз в кадр из потока рендера: превращает готовые картинки в текстуры (не больше двух за кадр). */
	public void tick(class_310 client) {
		for (int i = 0; i < 2; i++) {
			Decoded d = ready.poll();

			if (d == null) {
				return;
			}

			class_1011 image = new class_1011(class_1011.class_1012.field_4997, d.width, d.height, false); // RGBA

			for (int y = 0; y < d.height; y++) {
				for (int x = 0; x < d.width; x++) {
					image.method_4305(x, y, d.abgr[y * d.width + x]); // setColor (ABGR)
				}
			}

			class_1043 texture = new class_1043(image);
			texture.method_4527(true, false); // setFilter(blur, mipmap): сглаживание при уменьшении
			class_2960 id = class_2960.method_60655("argentum-client", "icons/i" + (counter++));
			client.method_1531().method_4616(id, texture); // getTextureManager().registerTexture

			d.icon.width = d.width;
			d.icon.height = d.height;
			d.icon.shownAtNanos = System.nanoTime();
			d.icon.texture = id;
		}
	}

	private void load(ModrinthProject project, Icon icon) {
		// 1) свежая копия с диска — без сети
		if (tryDecode(icon, cache.icon(project.id, ICON_TTL_MS))) {
			return;
		}

		// 2) скачиваем
		List<String> candidates = new ArrayList<>();
		String url = project.iconUrl;

		if (url != null && !url.toLowerCase().endsWith(".webp")) {
			candidates.add(url);
		}

		if (url != null) {
			Matcher m = CDN_ID.matcher(url);

			if (m.find()) {
				candidates.add("https://cdn.modrinth.com/data/" + m.group(1) + "/icon.png");
			}
		}

		for (String candidate : candidates) {
			try {
				byte[] bytes = download(candidate);

				if (tryDecode(icon, bytes)) {
					cache.putIcon(project.id, bytes);
					return;
				}
			} catch (Exception ignored) {
				// пробуем следующий вариант
			}
		}

		// 3) сети нет или не помогла — лучше устаревшая иконка, чем плейсхолдер
		if (!tryDecode(icon, cache.icon(project.id, Long.MAX_VALUE))) {
			icon.failed = true;
		}
	}

	private boolean tryDecode(Icon icon, byte[] bytes) {
		if (bytes == null) {
			return false;
		}

		Decoded decoded = decode(icon, bytes);

		if (decoded == null) {
			return false;
		}

		ready.add(decoded);

		return true;
	}

	private static byte[] download(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(15_000);
		c.setReadTimeout(20_000);
		c.setRequestProperty("User-Agent", "ArgentumLoader (github.com/GanichGG/ArgentumLoader)");

		if (c.getResponseCode() != 200) {
			throw new java.io.IOException("HTTP " + c.getResponseCode());
		}

		try (InputStream is = c.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int read;

			while ((read = is.read(buf)) >= 0) {
				out.write(buf, 0, read);

				if (out.size() > MAX_DOWNLOAD) {
					throw new java.io.IOException("слишком большая иконка");
				}
			}

			return out.toByteArray();
		}
	}

	private static Decoded decode(Icon icon, byte[] bytes) {
		if (WebP.isWebP(bytes)) {
			try {
				WebP.Image image = WebP.decode(bytes);
				ByteBuffer rgba = ByteBuffer.allocate(image.argb.length * 4);

				for (int p : image.argb) {
					rgba.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
				}

				rgba.flip();

				return downscale(icon, rgba, image.width, image.height);
			} catch (IOException | RuntimeException e) {
				return null; // побитый или анимированный WebP — пойдёт запасной вариант
			}
		}

		ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
		data.put(bytes).flip();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer w = stack.mallocInt(1);
			IntBuffer h = stack.mallocInt(1);
			IntBuffer comp = stack.mallocInt(1);
			ByteBuffer pixels = STBImage.stbi_load_from_memory(data, w, h, comp, 4);

			if (pixels == null) {
				return null; // не PNG/JPEG/GIF/BMP
			}

			try {
				return downscale(icon, pixels, w.get(0), h.get(0));
			} finally {
				STBImage.stbi_image_free(pixels);
			}
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	/** Уменьшает RGBA-картинку до не более MAX_SIDE по большей стороне (усреднением по альфе). */
	private static Decoded downscale(Icon icon, ByteBuffer pixels, int width, int height) {
		int step = Math.max(1, (Math.max(width, height) + MAX_SIDE - 1) / MAX_SIDE); // во сколько раз уменьшаем
		int outW = Math.max(1, width / step);
		int outH = Math.max(1, height / step);
		int[] out = new int[outW * outH];

		for (int y = 0; y < outH; y++) {
			for (int x = 0; x < outW; x++) {
				long r = 0;
				long g = 0;
				long b = 0;
				long a = 0;
				int count = 0;

				for (int dy = 0; dy < step; dy++) {
					for (int dx = 0; dx < step; dx++) {
						int sx = x * step + dx;
						int sy = y * step + dy;

						if (sx < width && sy < height) {
							int i = (sy * width + sx) * 4;
							int alpha = pixels.get(i + 3) & 0xFF;
							// усредняем с весом по альфе, чтобы прозрачные края не темнили цвет
							r += (pixels.get(i) & 0xFF) * alpha;
							g += (pixels.get(i + 1) & 0xFF) * alpha;
							b += (pixels.get(i + 2) & 0xFF) * alpha;
							a += alpha;
							count++;
						}
					}
				}

				int alphaAvg = count == 0 ? 0 : (int) (a / count);
				int rr = a == 0 ? 0 : (int) (r / a);
				int gg = a == 0 ? 0 : (int) (g / a);
				int bb = a == 0 ? 0 : (int) (b / a);
				out[y * outW + x] = (alphaAvg << 24) | (bb << 16) | (gg << 8) | rr; // ABGR для NativeImage
			}
		}

		return new Decoded(icon, outW, outH, out);
	}
}
