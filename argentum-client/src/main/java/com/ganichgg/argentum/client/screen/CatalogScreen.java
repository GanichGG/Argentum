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

import com.ganichgg.argentum.client.ui.ClientCache;
import com.ganichgg.argentum.client.ui.Fmt;
import com.ganichgg.argentum.client.ui.IconCache;
import com.ganichgg.argentum.client.ui.PixelIcons;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.modrinth.DependencyResolver;
import net.fabricmc.loader.impl.modrinth.InstalledModsIndex;
import net.fabricmc.loader.impl.modrinth.ModInstaller;
import net.fabricmc.loader.impl.modrinth.ModrinthCache;
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
 * Каталог модов: строчные карточки, живой поиск с задержкой, подгрузка списка при прокрутке, вкладка установленных,
 * выезжающая справа панель с описанием. Тёмная тема с белыми/серебряными акцентами, плоские фигуры без скруглений;
 * анимации плавные и негромкие (прокрутка, подсветка, переключатель вкладок, панель) — без масштабирования.
 *
 * <p>Данные кэшируются на диск ({@link ModrinthCache}): список популярного, карточки проектов (в том числе
 * установленных), описания и иконки — каталог открывается сразу и работает при плохом интернете.
 *
 * <p>Всё рисуется вручную: виджеты Minecraft не умеют скроллиться внутри списка, а своя отрисовка даёт контроль над
 * раскладкой — каждая строка обрезается по ширине своей колонки, поэтому ничто не налезает на соседнее.
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
	private static final long POPULAR_FRESH_MS = 10L * 60 * 1000;
	private static final long BODY_TTL_MS = 7L * 24 * 60 * 60 * 1000;
	private static final int PAGE_SIZE = 20;

	// Палитра: тёмные тона, белые и серебряные акценты
	private static final int C_BG = 0xFF0E0E11;
	private static final int C_HEADER = 0xFF121216;
	private static final int C_FIELD = 0xFF1A1A1F;
	private static final int C_PANEL = 0xFF18181C;
	private static final int C_PANEL_HOVER = 0xFF202027;
	private static final int C_BORDER = 0xFF2A2A31;
	private static final int C_CHIP = 0xFF26262D;
	private static final int C_WHITE = 0xFFFFFFFF;
	private static final int C_SILVER = 0xFFE4E4EA;
	private static final int C_TEXT = 0xFFD0D0D6;
	private static final int C_GRAY = 0xFF8E8E98;
	private static final int C_ACCENT = C_SILVER;
	private static final int C_ACCENT_HOVER = C_WHITE;
	private static final int C_ACCENT_TEXT = 0xFF111114;
	private static final int C_SECONDARY = 0xFF2E2E36;
	private static final int C_SECONDARY_HOVER = 0xFF3C3C46;
	private static final int C_DISABLED = 0xFF222228;
	private static final int C_RED = 0xFFFF8A8A;

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
	private static final int PANEL_W = 190;
	private static final int LINE_H = 11;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "argentum-catalog");
		t.setDaemon(true);
		return t;
	});

	private final class_437 parent;
	private final String gameVersion;
	private final ModrinthCache cache = ClientCache.get();
	private final IconCache icons = new IconCache(this.cache);

	private class_342 searchField;
	private String query = "";
	private String activeQuery = "";
	private Tab tab = Tab.CATALOG;
	private boolean started;

	private volatile List<ModrinthProject> catalogRows = Collections.emptyList();
	private volatile int catalogTotal;
	private volatile List<ModrinthProject> installedRows = Collections.emptyList();
	/** projectId -> имя файла, для того, что поставлено через каталог. */
	private volatile Map<String, String> installedFiles = Collections.emptyMap();
	private final Set<String> busyIds = ConcurrentHashMap.newKeySet();
	private volatile String status = "";
	private volatile int statusColor = C_GRAY;
	private volatile boolean loading;
	private volatile boolean loadingMore;
	private volatile int requestToken;
	private volatile long lastEditMs = -1;

	// плавность
	private float scroll;
	private float scrollTarget;
	private float tabPos; // 0 = каталог, 1 = установленные
	private float listFade = 1F; // проявление списка после переключения вкладки
	private long lastFrameNanos;
	private final Map<String, Float> hover = new HashMap<>();

	// панель описания
	private volatile ModrinthProject detail;
	private float panelAnim;
	private boolean panelOpen;
	private float detailScroll;
	private float detailScrollTarget;
	private volatile String detailBody; // простой текст; null — ещё нет
	private volatile int detailBodyState; // 0 нет, 1 грузится, 2 готово, 3 недоступно
	private List<String> detailLines = Collections.emptyList();
	private String detailLinesKey = "";
	// только для разработки: сценарий, который сам листает экран по таймеру (для скриншотов без ввода)
	private boolean devTimeline;
	private long devStart;
	private int devStep;
	private int btnX;
	private int btnY;
	private int btnW;
	private int btnH;

	public CatalogScreen(class_437 parent) {
		super(class_2561.method_43470("Моды Argentum"));
		this.parent = parent;
		this.gameVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.21.1");
	}

	/** Dev: включает сценарий самопроверки (панель, вкладки, подгрузка) — запускается файлом .argentum-open-catalog. */
	public CatalogScreen enableDevTimeline() {
		this.devTimeline = true;

		return this;
	}

	private void devTick() {
		if (this.devStart == 0) {
			this.devStart = System.currentTimeMillis();
		}

		long t = System.currentTimeMillis() - this.devStart;

		if (this.devStep == 0 && t > 3000 && this.catalogRows.size() > 2) {
			openDetail(this.catalogRows.get(2));
			this.devStep = 1;
		} else if (this.devStep == 1 && t > 9000) {
			closeDetail();
			this.devStep = 2;
		} else if (this.devStep == 2 && t > 11000) {
			switchTab(Tab.INSTALLED);
			this.devStep = 3;
		} else if (this.devStep == 3 && t > 16000) {
			switchTab(Tab.CATALOG);
			this.devStep = 4;
		} else if (this.devStep == 4 && t > 18000) {
			this.scrollTarget = maxScroll();
			this.devStep = 5;
		}
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

	/**
	 * Запуск поиска. Для пустого запроса (популярное) сразу показываем сохранённый список и обновляем его, если он
	 * старше десяти минут. Результаты устаревшего запроса отбрасываются по токену — быстрый ввод ничего не ломает.
	 */
	private void search(String q) {
		int token = ++this.requestToken;
		this.activeQuery = q;
		this.loadingMore = false;
		boolean popular = q.isEmpty();
		List<ModrinthProject> saved = popular ? this.cache.list("popular", -1) : null;
		boolean hadSaved = saved != null && !saved.isEmpty();

		if (hadSaved) {
			this.catalogRows = saved;
			this.catalogTotal = this.cache.listTotal("popular");
			this.scrollTarget = 0;
			this.scroll = 0;
		}

		if (hadSaved && this.cache.list("popular", POPULAR_FRESH_MS) != null) {
			this.loading = false;
			setStatus("Популярные моды для " + this.gameVersion, C_GRAY);
			return;
		}

		this.loading = true;
		setStatus(popular ? "Популярные моды для " + this.gameVersion : "Ищу «" + q + "»", C_GRAY);

		WORKER.submit(() -> {
			try {
				ModrinthClient.SearchPage page = ModrinthClient.searchPage(q, LOADER, this.gameVersion, 0, PAGE_SIZE);

				if (token == this.requestToken) {
					this.catalogRows = page.hits;
					this.catalogTotal = page.totalHits;
					this.scrollTarget = 0;
					this.scroll = 0;
					setStatus(page.hits.isEmpty() ? "Ничего не найдено под " + this.gameVersion : (popular ? "Популярные моды для " + this.gameVersion : "Найдено: " + page.totalHits), page.hits.isEmpty() ? C_RED : C_GRAY);
				}

				if (popular) {
					this.cache.putList("popular", page.hits, page.totalHits);
				} else {
					this.cache.putProjects(page.hits);
				}

				this.cache.save();
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Поиск «" + q + "» не удался", e);

				if (token == this.requestToken) {
					setStatus(hadSaved ? "Нет соединения — показан сохранённый список" : "Ошибка поиска: " + e.getMessage(), hadSaved ? C_GRAY : C_RED);
				}
			} finally {
				if (token == this.requestToken) {
					this.loading = false;
				}
			}
		});
	}

	/** Подгрузка следующей страницы, когда список прокручен почти до конца. */
	private void loadMore() {
		int token = this.requestToken;
		int offset = this.catalogRows.size();
		String q = this.activeQuery;

		if (this.loadingMore || this.loading || offset >= this.catalogTotal) {
			return;
		}

		this.loadingMore = true;

		WORKER.submit(() -> {
			try {
				ModrinthClient.SearchPage page = ModrinthClient.searchPage(q, LOADER, this.gameVersion, offset, PAGE_SIZE);
				this.cache.putProjects(page.hits);

				if (token == this.requestToken && this.catalogRows.size() == offset) {
					List<ModrinthProject> all = new ArrayList<>(this.catalogRows);
					all.addAll(page.hits);
					this.catalogRows = all;
					this.catalogTotal = page.totalHits;
				}

				this.cache.save();
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось подгрузить следующую страницу", e);
			} finally {
				this.loadingMore = false;
			}
		});
	}

	/** Установленные: мгновенно из кэша (или хотя бы по имени файла), затем обновляем данные из сети, если она есть. */
	private void loadInstalled() {
		int token = ++this.requestToken;
		refreshInstalledIndex();
		this.loadingMore = false;

		List<String> ids = new ArrayList<>(this.installedFiles.keySet());
		List<ModrinthProject> fromCache = new ArrayList<>();

		for (String id : ids) {
			ModrinthProject p = this.cache.project(id);
			fromCache.add(p != null ? p : fallbackProject(id));
		}

		this.installedRows = fromCache;
		this.scrollTarget = 0;
		this.scroll = 0;

		if (ids.isEmpty()) {
			this.loading = false;
			setStatus("Через каталог пока ничего не установлено", C_GRAY);
			return;
		}

		this.loading = true;
		setStatus("Установлено через каталог: " + ids.size(), C_GRAY);

		WORKER.submit(() -> {
			try {
				List<ModrinthProject> fresh = ModrinthClient.getProjects(ids);
				this.cache.putProjects(fresh);
				this.cache.save();

				if (token == this.requestToken) {
					List<ModrinthProject> ordered = new ArrayList<>();

					for (String id : ids) {
						ModrinthProject p = this.cache.project(id);
						ordered.add(p != null ? p : fallbackProject(id));
					}

					this.installedRows = ordered;
				}
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось обновить список установленных", e);

				if (token == this.requestToken) {
					setStatus("Нет соединения — показаны сохранённые данные (" + ids.size() + ")", C_GRAY);
				}
			} finally {
				if (token == this.requestToken) {
					this.loading = false;
				}
			}
		});
	}

	/** Карточка для мода, о котором в кэше ничего нет: хотя бы имя файла. */
	private ModrinthProject fallbackProject(String id) {
		String file = this.installedFiles.getOrDefault(id, id);
		String title = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;

		return new ModrinthProject(id, null, title, "", null, 0, 0, null, null, Collections.emptyList(), null, null, 0);
	}

	private void switchTab(Tab target) {
		if (target == this.tab) {
			return;
		}

		this.tab = target;
		this.listFade = 0F; // новый список плавно проявляется
		this.scroll = 0;
		this.scrollTarget = 0;
		closeDetail();

		if (target == Tab.INSTALLED) {
			loadInstalled();
		} else {
			this.requestToken++; // отменяем незавершённую загрузку списка установленных
			this.loading = false;
			setStatus(this.catalogRows.isEmpty() ? "" : "Популярные моды для " + this.gameVersion, C_GRAY);

			if (this.catalogRows.isEmpty()) {
				search(this.query.trim());
			}
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
				this.cache.putProjects(Collections.singletonList(p)); // карточка нужна вкладке «Установленные» без сети
				this.cache.save();
				refreshInstalledIndex();
				setStatus("Установлено: " + String.join(", ", result.installedModNames) + ". Заработает после перезапуска игры.", C_SILVER);
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

		if (this.detail != null && this.detail.id.equals(p.id)) {
			closeDetail();
		}

		setStatus("Удалено: " + fileName, C_SILVER);
	}

	/** Действие кнопки карточки/панели: поставить, либо удалить на вкладке установленных. */
	private void act(ModrinthProject p) {
		if (this.tab == Tab.INSTALLED) {
			remove(p);
		} else if (!this.installedFiles.containsKey(p.id)) {
			install(p);
		}
	}

	// ---------------------------------------------------------------- панель описания

	private void openDetail(ModrinthProject p) {
		if (this.detail != null && this.detail.id.equals(p.id) && this.panelOpen) {
			return;
		}

		this.detail = p;
		this.panelOpen = true;
		this.detailScroll = 0;
		this.detailScrollTarget = 0;
		this.detailBody = null;
		this.detailBodyState = 0;
		this.detailLinesKey = "";

		String saved = this.cache.body(p.id, BODY_TTL_MS);

		if (saved != null) {
			this.detailBody = saved;
			this.detailBodyState = 2;
			return;
		}

		this.detailBodyState = 1;

		WORKER.submit(() -> {
			try {
				String plain = Fmt.stripMarkdown(ModrinthClient.getProjectBody(p.id));
				this.cache.putBody(p.id, plain);

				if (this.detail != null && this.detail.id.equals(p.id)) {
					this.detailBody = plain;
					this.detailBodyState = 2;
				}
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось загрузить описание «" + p.title + "»", e);

				if (this.detail != null && this.detail.id.equals(p.id)) {
					this.detailBodyState = 3;
				}
			}
		});
	}

	private void closeDetail() {
		this.panelOpen = false;
	}

	private int panelX(int w) {
		return (int) (w - (PANEL_W + PAD) * this.panelAnim);
	}

	private boolean overPanel(double mx, double my) {
		int w = this.field_22789;

		return this.panelAnim > 0.4F && inside(mx, my, panelX(w), LIST_TOP + 4, PANEL_W, listBottom() - LIST_TOP - 8);
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

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private static boolean noStats(ModrinthProject p) {
		return p.dateModified == null && p.downloads == 0 && p.follows == 0;
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

		// панель описания: крестик, кнопка действия; остальное внутри панели просто не пропускаем к списку
		if (overPanel(mx, my)) {
			int px = panelX(w);

			if (inside(mx, my, px + PANEL_W - 8 - 14, LIST_TOP + 4 + 5, 14, 14)) {
				closeDetail();
			} else if (this.detail != null && inside(mx, my, this.btnX, this.btnY, this.btnW, this.btnH)) {
				act(this.detail);
			}

			return true;
		}

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
				ModrinthProject p = current.get(index);
				int cw = cardWidth();

				if (mx < PAD || mx >= PAD + cw) {
					return false;
				}

				if (inside(mx, my, PAD + cw - RIGHT_COL - 4, cardY + 30, RIGHT_COL, 14)) {
					act(p);
				} else {
					openDetail(p);
				}

				return true;
			}
		}

		return false;
	}

	@Override
	public boolean method_25401(double mx, double my, double horizontal, double vertical) {
		if (overPanel(mx, my)) {
			this.detailScrollTarget = Math.max(0, this.detailScrollTarget - (float) vertical * 30);

			return true;
		}

		this.scrollTarget = Math.max(0, Math.min(maxScroll(), this.scrollTarget - (float) vertical * 30));

		return true;
	}

	@Override
	public boolean method_25404(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 256 && this.panelOpen) { // Esc сначала закрывает панель, экран — со следующего нажатия
			closeDetail();
			return true;
		}

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

	/** Перенос по словам в заданную ширину; пустые строки сохраняются (абзацы). */
	private List<String> wrap(String s, int maxWidth) {
		List<String> lines = new ArrayList<>();

		if (s == null) {
			return lines;
		}

		for (String paragraph : s.split("\n", -1)) {
			if (paragraph.isEmpty()) {
				lines.add("");
				continue;
			}

			StringBuilder cur = new StringBuilder();

			for (String word : paragraph.split(" ")) {
				String candidate = cur.length() == 0 ? word : cur + " " + word;

				if (textWidth(candidate) > maxWidth && cur.length() > 0) {
					lines.add(cur.toString());
					cur = new StringBuilder();
					candidate = word;
				}

				// слишком длинное слово (ссылка и т.п.) режем по ширине
				while (textWidth(candidate) > maxWidth && candidate.length() > 1) {
					String head = this.field_22793.method_27523(candidate, maxWidth);

					if (head.isEmpty()) {
						break;
					}

					lines.add(head);
					candidate = candidate.substring(head.length());
				}

				cur = new StringBuilder(candidate);
			}

			lines.add(cur.toString());

			if (lines.size() > 600) { // защита от гигантских описаний
				break;
			}
		}

		return lines;
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

	private static float approach(float current, float target, float dt, float speed) {
		float next = current + (target - current) * (1 - (float) Math.exp(-dt * speed));

		return Math.abs(target - next) < 0.004F ? target : next;
	}

	// Рисуем в renderBackground: он вызывается до виджетов (поле поиска ляжет сверху), а ванильный блюр заменён нашим фоном.
	@Override
	public void method_25420(class_332 ctx, int mx, int my, float delta) {
		long now = System.nanoTime();
		float dt = this.lastFrameNanos == 0 ? 0.016F : Math.min(0.1F, (now - this.lastFrameNanos) / 1_000_000_000.0F);
		this.lastFrameNanos = now;

		this.icons.tick(this.field_22787);

		if (this.devTimeline) {
			devTick();
		}

		// живой поиск: запрос уходит, когда ввод затих
		if (this.lastEditMs > 0 && this.tab == Tab.CATALOG && System.currentTimeMillis() - this.lastEditMs > SEARCH_DEBOUNCE_MS) {
			this.lastEditMs = -1;
			search(this.query.trim());
		}

		// плавность: прокрутка, вкладки, панель
		this.scrollTarget = Math.max(0, Math.min(maxScroll(), this.scrollTarget));
		this.scroll = approach(this.scroll, this.scrollTarget, dt, 16);
		this.tabPos = approach(this.tabPos, this.tab == Tab.INSTALLED ? 1F : 0F, dt, 14);
		this.listFade = approach(this.listFade, 1F, dt, 12);
		this.panelAnim = approach(this.panelAnim, this.panelOpen ? 1F : 0F, dt, 13);
		this.detailScrollTarget = Math.max(0, this.detailScrollTarget);
		this.detailScroll = approach(this.detailScroll, this.detailScrollTarget, dt, 16);

		// подгрузка: когда до конца списка осталось меньше двух карточек
		if (this.tab == Tab.CATALOG && !this.rows().isEmpty() && this.scroll > maxScroll() - 2 * (CARD_H + GAP)) {
			loadMore();
		}

		int w = this.field_22789;
		int h = this.field_22790;

		ctx.method_25294(0, 0, w, h, C_BG);

		drawHeader(ctx, mx, my, w);
		drawList(ctx, mx, my, dt, w);
		drawPanel(ctx, mx, my, w);
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

		// вкладки: подчёркивание плавно переезжает, подписи плавно меняют цвет
		for (Tab t : Tab.values()) {
			float weight = t == Tab.CATALOG ? 1F - this.tabPos : this.tabPos;
			boolean hovered = inside(mx, my, tabX(t), TABS_Y, tabWidth(t), TABS_H);
			String label = t == Tab.CATALOG ? "Каталог" : "Установленные";
			int color = lerpColor(hovered ? C_TEXT : C_GRAY, C_WHITE, weight);
			text(ctx, label, tabX(t) + (tabWidth(t) - textWidth(label)) / 2, TABS_Y + 4, color);
		}

		int ux = (int) (tabX(Tab.CATALOG) + (tabX(Tab.INSTALLED) - tabX(Tab.CATALOG)) * this.tabPos);
		int uw = (int) (tabWidth(Tab.CATALOG) + (tabWidth(Tab.INSTALLED) - tabWidth(Tab.CATALOG)) * this.tabPos);
		ctx.method_25294(ux, TABS_Y + TABS_H - 2, ux + uw, TABS_Y + TABS_H, C_ACCENT);
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

		// индикатор подгрузки внизу списка
		if (this.loadingMore) {
			int y = (int) (LIST_TOP + current.size() * (CARD_H + GAP) - this.scroll);

			if (y < bottom) {
				text(ctx, "Загружаю ещё...", (w - textWidth("Загружаю ещё...")) / 2, y + 2, C_GRAY);
			}
		}

		// плавное проявление нового списка после смены вкладки: прикрываем его фоном с убывающей прозрачностью
		if (this.listFade < 1F) {
			int alpha = (int) ((1F - this.listFade) * 255);
			ctx.method_25294(0, LIST_TOP, w, bottom, (alpha << 24) | (C_BG & 0x00FFFFFF));
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
			ctx.method_25294(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, 0xFF6A6A76);
		}
	}

	private void drawCard(class_332 ctx, ModrinthProject p, int x, int y, int w, int mx, int my, float dt) {
		boolean hovered = inside(mx, my, x, y, w, CARD_H) && my >= LIST_TOP && my < listBottom() && !overPanel(mx, my);
		boolean selected = this.panelOpen && this.detail != null && this.detail.id.equals(p.id);
		float h = this.hover.getOrDefault(p.id, 0F);
		h = approach(h, hovered || selected ? 1F : 0F, dt, 14);
		this.hover.put(p.id, h);

		ctx.method_25294(x, y, x + w, y + CARD_H, lerpColor(C_PANEL, C_PANEL_HOVER, h));
		border(ctx, x, y, w, CARD_H, selected ? C_GRAY : C_BORDER);

		drawIcon(ctx, p, x + 4, y + 4, ICON);

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
		if (!noStats(p)) {
			drawStats(ctx, p, rightX, y + 5, RIGHT_COL);
		}

		drawActionButton(ctx, p, rightX, y + 30, RIGHT_COL, 14, mx, my);
	}

	/** Скачивания, лайки и «обновлён N назад» двумя строками в колонке заданной ширины. */
	private void drawStats(class_332 ctx, ModrinthProject p, int x, int y, int width) {
		String downloads = Fmt.compact(p.downloads);
		String follows = Fmt.compact(p.follows);

		PixelIcons.draw(ctx, PixelIcons.DOWNLOAD, x, y, C_GRAY);
		text(ctx, downloads, x + 10, y, C_TEXT);

		int heartX = x + 10 + textWidth(downloads) + 8;

		if (heartX + 10 + textWidth(follows) <= x + width) {
			PixelIcons.draw(ctx, PixelIcons.HEART, heartX, y, C_GRAY);
			text(ctx, follows, heartX + 10, y, C_TEXT);
		}

		String ago = Fmt.ago(p.dateModified);

		if (!ago.isEmpty()) {
			PixelIcons.draw(ctx, PixelIcons.CLOCK, x, y + 12, C_GRAY);
			text(ctx, fit(ago, width - 10), x + 10, y + 12, C_GRAY);
		}
	}

	private void drawActionButton(class_332 ctx, ModrinthProject p, int x, int y, int w, int h, int mx, int my) {
		boolean hovered = inside(mx, my, x, y, w, h) && !overPanel(mx, my);

		if (this.tab == Tab.INSTALLED) {
			boolean busy = this.busyIds.contains(p.id);
			flatButton(ctx, x, y, w, h, busy ? "Удаляю..." : "Удалить", busy ? C_DISABLED : C_SECONDARY, C_SECONDARY_HOVER, hovered && !busy, busy ? C_GRAY : C_WHITE);
		} else if (this.installedFiles.containsKey(p.id)) {
			flatButton(ctx, x, y, w, h, "Установлен", C_DISABLED, C_DISABLED, false, C_GRAY);
		} else if (this.busyIds.contains(p.id)) {
			flatButton(ctx, x, y, w, h, "Установка...", C_DISABLED, C_DISABLED, false, C_GRAY);
		} else {
			flatButton(ctx, x, y, w, h, "Установить", C_ACCENT, C_ACCENT_HOVER, hovered, C_ACCENT_TEXT);
		}
	}

	private void drawIcon(class_332 ctx, ModrinthProject p, int x, int y, int size) {
		IconCache.Icon icon = this.icons.get(p);

		if (icon.texture == null) {
			// плейсхолдер: цвет проекта и первая буква — пока иконка грузится (или если её не удалось получить)
			int color = p.color != 0 ? (0xFF000000 | p.color) : C_CHIP;
			ctx.method_25294(x, y, x + size, y + size, lerpColor(color, C_PANEL, 0.55F));

			if (p.title != null && !p.title.isEmpty()) {
				String letter = p.title.substring(0, 1).toUpperCase();
				text(ctx, letter, x + (size - textWidth(letter)) / 2, y + (size - 8) / 2 + 1, C_WHITE);
			}

			return;
		}

		// иконка плавно проявляется
		float alpha = Math.min(1F, (System.nanoTime() - icon.shownAtNanos) / 200_000_000F);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
		// DrawContext.drawTexture(Identifier, x, y, w, h, u, v, regionW, regionH, texW, texH)
		ctx.method_25293(icon.texture, x, y, size, size, 0F, 0F, icon.width, icon.height, icon.width, icon.height);
		RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
	}

	/** Выезжающая справа панель с описанием выбранного мода. */
	private void drawPanel(class_332 ctx, int mx, int my, int w) {
		ModrinthProject p = this.detail;

		if (p == null || this.panelAnim < 0.01F) {
			return;
		}

		int px = panelX(w);
		int py = LIST_TOP + 4;
		int ph = listBottom() - LIST_TOP - 8;
		int innerX = px + 8;
		int innerW = PANEL_W - 16;

		ctx.method_25294(px, py, px + PANEL_W, py + ph, C_HEADER);
		border(ctx, px, py, PANEL_W, ph, C_BORDER);

		// шапка: иконка, название, автор, крестик
		drawIcon(ctx, p, innerX, py + 8, 28);
		int titleX = innerX + 28 + 6;
		int titleW = innerW - 28 - 6 - 16;
		text(ctx, fit(p.title, titleW), titleX, py + 9, C_WHITE);

		if (p.author != null) {
			text(ctx, fit("by " + p.author, titleW), titleX, py + 21, C_GRAY);
		}

		int cx = px + PANEL_W - 8 - 14;
		boolean closeHover = inside(mx, my, cx, py + 5, 14, 14);
		ctx.method_25294(cx, py + 5, cx + 14, py + 19, closeHover ? C_SECONDARY_HOVER : C_SECONDARY);
		PixelIcons.draw(ctx, PixelIcons.CLOSE, cx + 3, py + 8, C_WHITE);

		int y = py + 42;

		if (!noStats(p)) {
			drawStats(ctx, p, innerX, y, innerW);
			y += 26;
		}

		// теги с переносом на следующие строки
		int chipX = innerX;

		for (String tag : Fmt.tags(p)) {
			int chipW = textWidth(tag) + 8;

			if (chipX + chipW > innerX + innerW) {
				chipX = innerX;
				y += 13;
			}

			ctx.method_25294(chipX, y, chipX + chipW, y + 11, C_CHIP);
			text(ctx, tag, chipX + 4, y + 2, C_GRAY);
			chipX += chipW + 4;
		}

		y += 16;

		// кнопка действия (её прямоугольник запоминаем для обработки клика)
		this.btnX = innerX;
		this.btnY = y;
		this.btnW = innerW;
		this.btnH = 14;
		drawActionButton(ctx, p, this.btnX, this.btnY, this.btnW, this.btnH, mx, my);
		y += 14 + 8;

		// текст: краткое описание, затем полное (если загружено); прокручивается колесом над панелью
		int textTop = y;
		int textBottom = py + ph - 6;
		int textW = innerW - SCROLLBAR_W - 3;
		List<String> lines = detailLines(p, textW);
		int contentH = lines.size() * LINE_H;
		float maxDetailScroll = Math.max(0, contentH - (textBottom - textTop));
		this.detailScrollTarget = Math.min(this.detailScrollTarget, maxDetailScroll);
		this.detailScroll = Math.min(this.detailScroll, maxDetailScroll);

		ctx.method_44379(innerX, textTop, innerX + innerW, textBottom);

		for (int i = 0; i < lines.size(); i++) {
			int ly = (int) (textTop + i * LINE_H - this.detailScroll);

			if (ly + LINE_H < textTop) {
				continue;
			}

			if (ly > textBottom) {
				break;
			}

			text(ctx, lines.get(i), innerX, ly, i < shortLineCount(p, textW) ? C_TEXT : C_GRAY);
		}

		ctx.method_44380();

		if (maxDetailScroll > 0) {
			int trackH = textBottom - textTop;
			int thumbH = Math.max(10, (int) (trackH * (trackH / (float) contentH)));
			int thumbY = textTop + (int) ((trackH - thumbH) * (this.detailScroll / maxDetailScroll));
			int sx = innerX + innerW - SCROLLBAR_W;
			ctx.method_25294(sx, textTop, sx + SCROLLBAR_W, textBottom, C_FIELD);
			ctx.method_25294(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, 0xFF6A6A76);
		}
	}

	private int shortLineCount(ModrinthProject p, int width) {
		return wrap(p.description, width).size();
	}

	/** Строки текста панели; пересчитываются только при смене мода, ширины или статуса загрузки описания. */
	private List<String> detailLines(ModrinthProject p, int width) {
		String key = p.id + "|" + width + "|" + this.detailBodyState;

		if (!key.equals(this.detailLinesKey)) {
			List<String> lines = new ArrayList<>(wrap(p.description, width));
			lines.add("");

			switch (this.detailBodyState) {
			case 2:
				lines.addAll(wrap(this.detailBody, width));
				break;
			case 3:
				lines.add("Полное описание недоступно без сети.");
				break;
			default:
				lines.add("Загружаю описание...");
			}

			this.detailLines = lines;
			this.detailLinesKey = key;
		}

		return this.detailLines;
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
