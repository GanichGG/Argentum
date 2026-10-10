package com.ganichgg.argentum.client.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.systems.RenderSystem;

import com.ganichgg.argentum.client.ui.Fmt;
import com.ganichgg.argentum.client.ui.IconCache;
import com.ganichgg.argentum.client.ui.PixelIcons;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.modrinth.DependencyResolver;
import net.fabricmc.loader.impl.modrinth.InstalledModsIndex;
import net.fabricmc.loader.impl.modrinth.ModInstaller;
import net.fabricmc.loader.impl.modrinth.ModrinthClient;
import net.fabricmc.loader.impl.modrinth.ModrinthProject;
import net.fabricmc.loader.impl.modrinth.ModrinthVersion;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_342;
import net.minecraft.class_437;

/**
 * Каталог модов: строчные карточки (иконка, название, автор, описание, скачивания, лайки, обновление, теги),
 * живой поиск с задержкой после ввода, вкладка установленных. Плоский стиль без скруглений; анимации только
 * плавные и негромкие (прокрутка, подсветка, появление иконок) — без масштабирования.
 *
 * <p>Всё рисуется вручную: виджеты Minecraft не умеют скроллиться внутри списка, а своя отрисовка даёт полный
 * контроль над раскладкой — ничто не должно налезать на соседнее, поэтому каждая строка обрезается по ширине своей колонки.
 *
 * <p>Intermediary (Yarn): class_437 = Screen, class_332 = DrawContext, class_342 = TextFieldWidget, class_2561 = Text.
 * Методы Screen: method_25426 init, method_25420 renderBackground (вызывается ДО виджетов — рисуем всё здесь, чтобы
 * ванильный блюр не лёг поверх), method_25419 close, method_25402 mouseClicked, method_25401 mouseScrolled,
 * method_25404 keyPressed, method_37063 addDrawableChild; поля field_22787 client, field_22793 textRenderer, field_22789/22790 width/height.
 */
public final class CatalogScreen extends class_437 {
	private enum Tab { CATALOG, INSTALLED }

	private static final String LOADER = "fabric";
	private static final long SEARCH_DEBOUNCE_MS = 450;

	// Палитра: плоские тёмные тона и один зелёный акцент
	private static final int C_BG = 0xFF0E0E11;
	private static final int C_HEADER = 0xFF121216;
	private static final int C_FIELD = 0xFF1A1A1F;
	private static final int C_PANEL = 0xFF18181C;
	private static final int C_PANEL_HOVER = 0xFF202027;
	private static final int C_BORDER = 0xFF2A2A31;
	private static final int C_CHIP = 0xFF26262D;
	private static final int C_WHITE = 0xFFFFFFFF;
	private static final int C_TEXT = 0xFFD0D0D6;
	private static final int C_GRAY = 0xFF8E8E98;
	private static final int C_ACCENT = 0xFF2FA866;
	private static final int C_ACCENT_HOVER = 0xFF3BC77A;
	private static final int C_DANGER = 0xFF9B3535;
	private static final int C_DANGER_HOVER = 0xFFB84444;
	private static final int C_DISABLED = 0xFF2E2E36;
	private static final int C_RED = 0xFFFF6B6B;
	private static final int C_GREEN = 0xFF6BE39A;

	// Раскладка (в единицах GUI)
	private static final int PAD = 8;
	private static final int SEARCH_Y = 8;
	private static final int FIELD_H = 20;
	private static final int TABS_Y = 34;
	private static final int TABS_H = 16;
	private static final int LIST_TOP = 54;
	private static final int STATUS_H = 20;
	private static final int CARD_H = 48;
	private static final int GAP = 4;
	private static final int ICON = 40;
	private static final int RIGHT_COL = 112;
	private static final int SCROLLBAR_W = 3;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "argentum-catalog");
		t.setDaemon(true);
		return t;
	});

	private final class_437 parent;
	private final String gameVersion;
	private final IconCache icons = new IconCache();

	private class_342 searchField;
	private String query = "";
	private Tab tab = Tab.CATALOG;
	private boolean started;

	private volatile List<ModrinthProject> catalogRows = Collections.emptyList();
	private volatile List<ModrinthProject> installedRows = Collections.emptyList();
	/** projectId -> имя файла, для того, что поставлено через каталог. */
	private volatile Map<String, String> installedFiles = Collections.emptyMap();
	private final Set<String> busyIds = ConcurrentHashMap.newKeySet();
	private volatile String status = "";
	private volatile int statusColor = C_GRAY;
	private volatile boolean loading;
	private volatile int requestToken;
	private volatile long lastEditMs = -1;

	private float scroll;
	private float scrollTarget;
	private long lastFrameNanos;
	private final Map<String, Float> hover = new HashMap<>();

	public CatalogScreen(class_437 parent) {
		super(class_2561.method_43470("Моды Argentum"));
		this.parent = parent;
		this.gameVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.21.1");
	}

	private static class_2561 text(String s) {
		return class_2561.method_43470(s);
	}

	private Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	// ---------------------------------------------------------------- жизненный цикл

	@Override
	protected void method_25426() {
		int w = this.field_22789;

		this.searchField = new class_342(this.field_22793, PAD + 5, SEARCH_Y + 6, w - PAD * 2 - FIELD_H - 4 - 10, 10, text(""));
		this.searchField.method_1858(false); // свой фон рисуем сами
		this.searchField.method_1880(80);
		this.searchField.method_47404(text("Поиск модов на Modrinth"));
		this.searchField.method_1852(this.query);
		this.searchField.method_1863(s -> {
			this.query = s;
			this.lastEditMs = System.currentTimeMillis();
		});
		this.method_37063(this.searchField);

		if (!this.started) {
			this.started = true;
			refreshInstalledIndex();
			search("");
		}
	}

	@Override
	public void method_25419() {
		this.field_22787.method_1507(this.parent);
	}

	// ---------------------------------------------------------------- данные

	private void setStatus(String s, int color) {
		this.status = s;
		this.statusColor = color;
	}

	private List<ModrinthProject> rows() {
		return this.tab == Tab.CATALOG ? this.catalogRows : this.installedRows;
	}

	private void refreshInstalledIndex() {
		InstalledModsIndex index = InstalledModsIndex.load(modsDir());
		Map<String, String> files = new LinkedHashMap<>();

		for (Map.Entry<String, InstalledModsIndex.Entry> e : index.entries().entrySet()) {
			files.put(e.getKey(), e.getValue().fileName);
		}

		this.installedFiles = files;
	}

	/** Запуск поиска. Результаты устаревшего запроса отбрасываются по токену — быстрый ввод ничего не ломает. */
	private void search(String q) {
		int token = ++this.requestToken;
		this.loading = true;
		setStatus(q.isEmpty() ? "Популярные моды для " + this.gameVersion : "Ищу «" + q + "»", C_GRAY);

		WORKER.submit(() -> {
			try {
				List<ModrinthProject> found = ModrinthClient.search(q, LOADER, this.gameVersion);

				if (token == this.requestToken) {
					this.catalogRows = found;
					this.scrollTarget = 0;
					this.scroll = 0;
					setStatus(found.isEmpty() ? "Ничего не найдено под " + this.gameVersion : (q.isEmpty() ? "Популярные моды для " + this.gameVersion : "Найдено: " + found.size()), found.isEmpty() ? C_RED : C_GRAY);
				}
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Поиск «" + q + "» не удался", e);

				if (token == this.requestToken) {
					setStatus("Ошибка поиска: " + e.getMessage(), C_RED);
				}
			} finally {
				if (token == this.requestToken) {
					this.loading = false;
				}
			}
		});
	}

	private void loadInstalled() {
		int token = ++this.requestToken;
		refreshInstalledIndex();
		this.loading = true;
		this.installedRows = Collections.emptyList();
		this.scrollTarget = 0;
		this.scroll = 0;

		List<String> ids = new ArrayList<>(this.installedFiles.keySet());

		if (ids.isEmpty()) {
			this.loading = false;
			setStatus("Через каталог пока ничего не установлено", C_GRAY);
			return;
		}

		setStatus("Загружаю список установленных...", C_GRAY);

		WORKER.submit(() -> {
			try {
				List<ModrinthProject> projects = ModrinthClient.getProjects(ids);

				if (token == this.requestToken) {
					this.installedRows = projects;
					setStatus("Установлено через каталог: " + projects.size(), C_GRAY);
				}
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось загрузить список установленных", e);

				if (token == this.requestToken) {
					setStatus("Ошибка: " + e.getMessage(), C_RED);
				}
			} finally {
				if (token == this.requestToken) {
					this.loading = false;
				}
			}
		});
	}

	private void switchTab(Tab target) {
		if (target == this.tab) {
			return;
		}

		this.tab = target;
		this.scroll = 0;
		this.scrollTarget = 0;

		if (target == Tab.INSTALLED) {
			loadInstalled();
		} else {
			this.requestToken++; // отменяем незавершённую загрузку списка установленных
			this.loading = false;
			setStatus(this.catalogRows.isEmpty() ? "" : "Найдено: " + this.catalogRows.size(), C_GRAY);
		}
	}

	private void install(ModrinthProject p) {
		if (!this.busyIds.add(p.id)) {
			return;
		}

		setStatus("Устанавливаю «" + p.title + "» и его зависимости...", C_GRAY);

		WORKER.submit(() -> {
			try {
				InstalledModsIndex index = InstalledModsIndex.load(modsDir());
				List<ModrinthVersion> plan = DependencyResolver.resolve(p.id, LOADER, this.gameVersion, index);

				if (plan.isEmpty()) {
					setStatus("«" + p.title + "» уже установлен либо нет версии под " + this.gameVersion, C_RED);
					return;
				}

				ModInstaller.InstallResult result = ModInstaller.install(modsDir(), plan);
				refreshInstalledIndex();
				setStatus("Установлено: " + String.join(", ", result.installedModNames) + ". Заработает после перезапуска игры.", C_GREEN);
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Установка «" + p.title + "» не удалась", e);
				setStatus("Ошибка установки: " + e.getMessage() + (e.getCause() != null ? " (" + e.getCause() + ")" : ""), C_RED);
			} finally {
				this.busyIds.remove(p.id);
			}
		});
	}

	private void remove(ModrinthProject p) {
		String fileName = this.installedFiles.get(p.id);

		if (fileName == null || !this.busyIds.add(p.id)) {
			return;
		}

		try {
			Files.deleteIfExists(modsDir().resolve(fileName));
		} catch (IOException e) {
			// Windows не даёт удалить jar, который игра сейчас держит открытым
			setStatus("Не удалось удалить " + fileName + " — файл занят игрой. Закрой игру и удали его вручную.", C_RED);
			this.busyIds.remove(p.id);
			return;
		}

		try {
			InstalledModsIndex index = InstalledModsIndex.load(modsDir());
			index.remove(p.id);
			index.save();
		} catch (IOException e) {
			setStatus("Файл удалён, но реестр не обновился: " + e.getMessage(), C_RED);
			this.busyIds.remove(p.id);
			return;
		}

		refreshInstalledIndex();
		List<ModrinthProject> left = new ArrayList<>(this.installedRows);
		left.removeIf(x -> x.id.equals(p.id));
		this.installedRows = left;
		this.busyIds.remove(p.id);
		setStatus("Удалено: " + fileName, C_GREEN);
	}

	// ---------------------------------------------------------------- раскладка

	private int cardWidth() {
		return this.field_22789 - PAD * 2 - SCROLLBAR_W - 3;
	}

	private int listBottom() {
		return this.field_22790 - STATUS_H;
	}

	private float contentHeight() {
		int n = rows().size();

		return n == 0 ? 0 : n * (CARD_H + GAP) - GAP;
	}

	private float maxScroll() {
		return Math.max(0, contentHeight() - (listBottom() - LIST_TOP));
	}

	private int tabX(Tab t) {
		return t == Tab.CATALOG ? PAD : PAD + 78;
	}

	private int tabWidth(Tab t) {
		return t == Tab.CATALOG ? 70 : 100;
	}

	private int buttonX(int cardX, int cardW) {
		return cardX + cardW - RIGHT_COL - 4;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// ---------------------------------------------------------------- ввод

	@Override
	public boolean method_25402(double mx, double my, int button) {
		if (super.method_25402(mx, my, button)) {
			return true;
		}

		if (button != 0) {
			return false;
		}

		int w = this.field_22789;
		int h = this.field_22790;

		// лупа: поиск сразу, без ожидания
		if (inside(mx, my, w - PAD - FIELD_H, SEARCH_Y, FIELD_H, FIELD_H)) {
			if (this.tab == Tab.CATALOG) {
				this.lastEditMs = -1;
				search(this.query.trim());
			}

			return true;
		}

		for (Tab t : Tab.values()) {
			if (inside(mx, my, tabX(t), TABS_Y, tabWidth(t), TABS_H)) {
				switchTab(t);
				return true;
			}
		}

		if (inside(mx, my, w - PAD - 52, h - STATUS_H + 3, 52, 14)) {
			this.method_25419();
			return true;
		}

		if (my >= LIST_TOP && my < listBottom()) {
			int index = (int) ((my - LIST_TOP + this.scroll) / (CARD_H + GAP));
			List<ModrinthProject> current = rows();

			if (index >= 0 && index < current.size()) {
				int cardY = (int) (LIST_TOP + index * (CARD_H + GAP) - this.scroll);
				int bx = buttonX(PAD, cardWidth());

				if (inside(mx, my, bx, cardY + 30, RIGHT_COL, 14)) {
					ModrinthProject p = current.get(index);

					if (this.tab == Tab.INSTALLED) {
						remove(p);
					} else if (!this.installedFiles.containsKey(p.id)) {
						install(p);
					}

					return true;
				}
			}
		}

		return false;
	}

	@Override
	public boolean method_25401(double mx, double my, double horizontal, double vertical) {
		this.scrollTarget = Math.max(0, Math.min(maxScroll(), this.scrollTarget - (float) vertical * 30));

		return true;
	}

	@Override
	public boolean method_25404(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == 257 || keyCode == 335) && this.searchField.method_25370() && this.tab == Tab.CATALOG) { // Enter
			this.lastEditMs = -1;
			search(this.query.trim());
			return true;
		}

		return super.method_25404(keyCode, scanCode, modifiers);
	}

	// ---------------------------------------------------------------- отрисовка

	private void text(class_332 ctx, String s, int x, int y, int color) {
		ctx.method_51433(this.field_22793, s, x, y, color, false); // drawText без тени — плоский стиль
	}

	private int textWidth(String s) {
		return this.field_22793.method_1727(s);
	}

	private String fit(String s, int maxWidth) {
		if (s == null || maxWidth <= 0) {
			return "";
		}

		if (textWidth(s) <= maxWidth) {
			return s;
		}

		int dots = textWidth("...");

		return maxWidth <= dots ? "" : this.field_22793.method_27523(s, maxWidth - dots) + "...";
	}

	private static int lerpColor(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF;
		int ag = (a >> 8) & 0xFF;
		int ab = a & 0xFF;
		int br = (b >> 16) & 0xFF;
		int bg = (b >> 8) & 0xFF;
		int bb = b & 0xFF;

		return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
	}

	private static void border(class_332 ctx, int x, int y, int w, int h, int color) {
		ctx.method_25294(x, y, x + w, y + 1, color);
		ctx.method_25294(x, y + h - 1, x + w, y + h, color);
		ctx.method_25294(x, y, x + 1, y + h, color);
		ctx.method_25294(x + w - 1, y, x + w, y + h, color);
	}

	private void flatButton(class_332 ctx, int x, int y, int w, int h, String label, int base, int hoverColor, boolean hovered, int textColor) {
		ctx.method_25294(x, y, x + w, y + h, hovered ? hoverColor : base);
		String s = fit(label, w - 6);
		text(ctx, s, x + (w - textWidth(s)) / 2, y + (h - 8) / 2 + 1, textColor);
	}

	// Рисуем в renderBackground: он вызывается до виджетов (поле поиска ляжет сверху), а ванильный блюр заменён нашим фоном.
	@Override
	public void method_25420(class_332 ctx, int mx, int my, float delta) {
		long now = System.nanoTime();
		float dt = this.lastFrameNanos == 0 ? 0.016F : Math.min(0.1F, (now - this.lastFrameNanos) / 1_000_000_000.0F);
		this.lastFrameNanos = now;

		this.icons.tick(this.field_22787);

		// живой поиск: запрос уходит, когда ввод затих
		if (this.lastEditMs > 0 && this.tab == Tab.CATALOG && System.currentTimeMillis() - this.lastEditMs > SEARCH_DEBOUNCE_MS) {
			this.lastEditMs = -1;
			search(this.query.trim());
		}

		// плавная прокрутка
		this.scrollTarget = Math.max(0, Math.min(maxScroll(), this.scrollTarget));
		this.scroll += (this.scrollTarget - this.scroll) * (1 - (float) Math.exp(-dt * 16));

		if (Math.abs(this.scrollTarget - this.scroll) < 0.05F) {
			this.scroll = this.scrollTarget;
		}

		int w = this.field_22789;
		int h = this.field_22790;

		ctx.method_25294(0, 0, w, h, C_BG);

		drawHeader(ctx, mx, my, w);
		drawList(ctx, mx, my, dt, w);
		drawStatusBar(ctx, mx, my, w, h);
	}

	private void drawHeader(class_332 ctx, int mx, int my, int w) {
		ctx.method_25294(0, 0, w, LIST_TOP - 2, C_HEADER);
		ctx.method_25294(0, LIST_TOP - 2, w, LIST_TOP - 1, C_BORDER);

		// поле поиска (текст рисует виджет поверх)
		int fieldW = w - PAD * 2 - FIELD_H - 4;
		ctx.method_25294(PAD, SEARCH_Y, PAD + fieldW, SEARCH_Y + FIELD_H, C_FIELD);
		border(ctx, PAD, SEARCH_Y, fieldW, FIELD_H, this.searchField.method_25370() ? C_ACCENT : C_BORDER);

		// кнопка-лупа
		int lx = w - PAD - FIELD_H;
		boolean lupaHover = inside(mx, my, lx, SEARCH_Y, FIELD_H, FIELD_H);
		ctx.method_25294(lx, SEARCH_Y, lx + FIELD_H, SEARCH_Y + FIELD_H, lupaHover ? C_PANEL_HOVER : C_FIELD);
		border(ctx, lx, SEARCH_Y, FIELD_H, FIELD_H, C_BORDER);
		PixelIcons.draw(ctx, PixelIcons.SEARCH, lx + (FIELD_H - PixelIcons.width(PixelIcons.SEARCH)) / 2, SEARCH_Y + (FIELD_H - PixelIcons.height(PixelIcons.SEARCH)) / 2, C_WHITE);

		// вкладки
		for (Tab t : Tab.values()) {
			boolean active = t == this.tab;
			boolean hovered = inside(mx, my, tabX(t), TABS_Y, tabWidth(t), TABS_H);
			String label = t == Tab.CATALOG ? "Каталог" : "Установленные";
			int color = active ? C_WHITE : hovered ? C_TEXT : C_GRAY;
			text(ctx, label, tabX(t) + (tabWidth(t) - textWidth(label)) / 2, TABS_Y + 4, color);

			if (active) {
				ctx.method_25294(tabX(t), TABS_Y + TABS_H - 2, tabX(t) + tabWidth(t), TABS_Y + TABS_H, C_ACCENT);
			}
		}
	}

	private void drawList(class_332 ctx, int mx, int my, float dt, int w) {
		List<ModrinthProject> current = rows();
		int bottom = listBottom();

		if (current.isEmpty()) {
			String message = this.loading ? "Загрузка..." : this.tab == Tab.CATALOG ? "Ничего не найдено" : "Здесь появятся моды, установленные через каталог";
			text(ctx, message, (w - textWidth(message)) / 2, LIST_TOP + (bottom - LIST_TOP) / 2 - 4, C_GRAY);

			return;
		}

		int cardW = cardWidth();

		ctx.method_44379(0, LIST_TOP, w, bottom); // enableScissor

		int first = Math.max(0, (int) (this.scroll / (CARD_H + GAP)));

		for (int i = first; i < current.size(); i++) {
			int y = (int) (LIST_TOP + i * (CARD_H + GAP) - this.scroll);

			if (y >= bottom) {
				break;
			}

			drawCard(ctx, current.get(i), PAD, y, cardW, mx, my, dt);
		}

		ctx.method_44380(); // disableScissor

		// полоса прокрутки
		float max = maxScroll();

		if (max > 0) {
			int trackH = bottom - LIST_TOP;
			int thumbH = Math.max(14, (int) (trackH * (trackH / contentHeight())));
			int thumbY = LIST_TOP + (int) ((trackH - thumbH) * (this.scroll / max));
			int sx = w - PAD - SCROLLBAR_W;
			ctx.method_25294(sx, LIST_TOP, sx + SCROLLBAR_W, bottom, C_FIELD);
			ctx.method_25294(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, 0xFF55555F);
		}
	}

	private void drawCard(class_332 ctx, ModrinthProject p, int x, int y, int w, int mx, int my, float dt) {
		boolean hovered = inside(mx, my, x, y, w, CARD_H) && my >= LIST_TOP && my < listBottom();
		float h = this.hover.getOrDefault(p.id, 0F);
		h += ((hovered ? 1F : 0F) - h) * (1 - (float) Math.exp(-dt * 14));
		this.hover.put(p.id, h);

		ctx.method_25294(x, y, x + w, y + CARD_H, lerpColor(C_PANEL, C_PANEL_HOVER, h));
		border(ctx, x, y, w, CARD_H, C_BORDER);

		drawIcon(ctx, p, x + 4, y + 4);

		int textX = x + 4 + ICON + 8;
		int rightX = x + w - RIGHT_COL - 4;
		int textW = rightX - textX - 6;

		// название + «by автор»
		String title = fit(p.title, textW);
		text(ctx, title, textX, y + 5, C_WHITE);

		if (p.author != null) {
			text(ctx, fit("by " + p.author, textW - textWidth(title) - 4), textX + textWidth(title) + 4, y + 5, C_GRAY);
		}

		// описание — как на Modrinth, одной строкой с многоточием
		text(ctx, fit(p.description, textW), textX, y + 17, C_TEXT);

		// теги
		int chipX = textX;

		for (String tag : Fmt.tags(p)) {
			int chipW = textWidth(tag) + 8;

			if (chipX + chipW > textX + textW) {
				break;
			}

			ctx.method_25294(chipX, y + 31, chipX + chipW, y + 42, C_CHIP);
			text(ctx, tag, chipX + 4, y + 33, C_GRAY);
			chipX += chipW + 4;
		}

		// правая колонка: скачивания, лайки, обновление, кнопка
		int cx = rightX;
		String downloads = Fmt.compact(p.downloads);
		String follows = Fmt.compact(p.follows);

		PixelIcons.draw(ctx, PixelIcons.DOWNLOAD, cx, y + 5, C_GRAY);
		text(ctx, downloads, cx + 10, y + 5, C_TEXT);

		int heartX = cx + 10 + textWidth(downloads) + 8;

		if (heartX + 10 + textWidth(follows) <= cx + RIGHT_COL) {
			PixelIcons.draw(ctx, PixelIcons.HEART, heartX, y + 5, C_GRAY);
			text(ctx, follows, heartX + 10, y + 5, C_TEXT);
		}

		String ago = Fmt.ago(p.dateModified);

		if (!ago.isEmpty()) {
			PixelIcons.draw(ctx, PixelIcons.CLOCK, cx, y + 17, C_GRAY);
			text(ctx, fit(ago, RIGHT_COL - 10), cx + 10, y + 17, C_GRAY);
		}

		drawActionButton(ctx, p, cx, y + 30, mx, my);
	}

	private void drawActionButton(class_332 ctx, ModrinthProject p, int x, int y, int mx, int my) {
		boolean hovered = inside(mx, my, x, y, RIGHT_COL, 14);

		if (this.tab == Tab.INSTALLED) {
			boolean busy = this.busyIds.contains(p.id);
			flatButton(ctx, x, y, RIGHT_COL, 14, busy ? "Удаляю..." : "Удалить", busy ? C_DISABLED : C_DANGER, C_DANGER_HOVER, hovered && !busy, C_WHITE);
		} else if (this.installedFiles.containsKey(p.id)) {
			flatButton(ctx, x, y, RIGHT_COL, 14, "Установлен", C_DISABLED, C_DISABLED, false, C_GRAY);
		} else if (this.busyIds.contains(p.id)) {
			flatButton(ctx, x, y, RIGHT_COL, 14, "Установка...", C_DISABLED, C_DISABLED, false, C_GRAY);
		} else {
			flatButton(ctx, x, y, RIGHT_COL, 14, "Установить", C_ACCENT, C_ACCENT_HOVER, hovered, C_WHITE);
		}
	}

	private void drawIcon(class_332 ctx, ModrinthProject p, int x, int y) {
		IconCache.Icon icon = this.icons.get(p);

		if (icon.texture == null) {
			// плейсхолдер: цвет проекта и первая буква — пока иконка грузится (или если её не удалось получить)
			int color = p.color != 0 ? (0xFF000000 | p.color) : C_CHIP;
			ctx.method_25294(x, y, x + ICON, y + ICON, lerpColor(color, C_PANEL, 0.55F));

			if (p.title != null && !p.title.isEmpty()) {
				String letter = p.title.substring(0, 1).toUpperCase();
				text(ctx, letter, x + (ICON - textWidth(letter)) / 2, y + (ICON - 8) / 2 + 1, C_WHITE);
			}

			return;
		}

		// иконка плавно проявляется
		float alpha = Math.min(1F, (System.nanoTime() - icon.shownAtNanos) / 200_000_000F);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
		// DrawContext.drawTexture(Identifier, x, y, w, h, u, v, regionW, regionH, texW, texH)
		ctx.method_25293(icon.texture, x, y, ICON, ICON, 0F, 0F, icon.width, icon.height, icon.width, icon.height);
		RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
	}

	private void drawStatusBar(class_332 ctx, int mx, int my, int w, int h) {
		int top = h - STATUS_H;
		ctx.method_25294(0, top, w, h, C_HEADER);
		ctx.method_25294(0, top, w, top + 1, C_BORDER);

		int backW = 52;
		text(ctx, fit(this.status, w - PAD * 2 - backW - 8), PAD, top + 6, this.statusColor);

		boolean backHover = inside(mx, my, w - PAD - backW, top + 3, backW, 14);
		flatButton(ctx, w - PAD - backW, top + 3, backW, 14, "Назад", C_FIELD, C_PANEL_HOVER, backHover, C_TEXT);
	}
}
