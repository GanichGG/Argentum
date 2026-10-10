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

import com.ganichgg.argentum.client.handoff.Handoff;
import com.ganichgg.argentum.client.ui.ClientCache;
import com.ganichgg.argentum.client.ui.Fmt;
import com.ganichgg.argentum.client.ui.Icon3D;
import com.ganichgg.argentum.client.ui.IconCache;
import com.ganichgg.argentum.client.ui.ModConfigs;
import com.ganichgg.argentum.client.ui.PixelIcons;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.hotload.HotLoadReport;
import net.fabricmc.loader.impl.hotload.HotLoader;
import net.fabricmc.loader.impl.hotload.PendingRemovals;
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
	private static final int C_BG = 0xFF1C1D1D;
	private static final int C_HEADER = 0xFF232425;
	private static final int C_FIELD = 0xFF151616;
	private static final int C_PANEL = 0xFF2A2B2C;
	private static final int C_PANEL_HOVER = 0xFF323334;
	private static final int C_BLACK = 0xFF0E0E0F;
	private static final int C_BORDER = C_BLACK;
	private static final int C_CHIP = 0xFF39393B;
	private static final int C_WHITE = 0xFFFFFFFF;
	private static final int C_TEXT = 0xFFD0D0D6;
	private static final int C_GRAY = 0xFF8E8E98;
	private static final int C_ACCENT = 0xFF0061FE;
	private static final int C_ACCENT_HOVER = 0xFF3A84FF;
	private static final int C_ACCENT_PRESS = 0xFF6FA6FF;
	private static final int C_ACCENT_TEXT = C_WHITE;
	private static final int C_OK = 0xFF8AB8FF;
	private static final int C_WARN = 0xFFFFC857;
	private static final int C_SECONDARY = 0xFF39393B;
	private static final int C_SECONDARY_HOVER = 0xFF47484A;
	private static final int C_DISABLED = 0xFF2E2F30;
	private static final int C_RED = 0xFFFF8A8A;
	private static final int GEAR_W = 18;
	private static final int BTN_H = 18; // высота кнопок в карточке и строке состояния
	private static final int BTN_Y = 32; // кнопки в карточке: отступ сверху
	private static final float TILT_MAX = 0.42F; // наклон иконки к курсору, радианы (~24°)

	// Раскладка (в единицах GUI)
	private static final int PAD = 8;
	private static final int SEARCH_Y = 8;
	private static final int FIELD_H = 24;
	private static final int TABS_Y = 37;
	private static final int TABS_H = 16;
	private static final int LIST_TOP = 58;
	private static final int STATUS_H = 24;
	private static final int CARD_H = 56;
	private static final int GAP = 4;
	private static final int ICON = 40;
	private static final int RIGHT_COL = 112;
	private static final int SCROLLBAR_W = 5;
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
	private final Map<String, Float> appear = new HashMap<>(); // появление карточек: с небольшой задержкой друг за другом
	private volatile int listEpoch; // растёт, когда список заменили целиком — карточки появляются заново
	private int appearEpoch;
	private float focusAnim;
	private float loadAnim; // видимость бегущей полоски загрузки под шапкой
	private float dt;
	private final Map<String, Float> btnHover = new HashMap<>();
	private final Map<String, Float> flash = new HashMap<>(); // вспышка кнопки после нажатия
	private final Map<String, float[]> tilt = new HashMap<>(); // наклон иконки к курсору: {вокруг вертикали, вокруг горизонтали}
	/** Ход установки: -2 — не идёт, -1 — идёт без известного прогресса, 0..1 — скачивание. */
	private volatile float installFraction = -2F;
	private final Map<String, Float> progressById = new ConcurrentHashMap<>();
	private float installShown;
	/** Карточки, которые сейчас уходят из списка после удаления: id -> ход анимации 0..1. */
	private final Map<String, Float> leaving = new HashMap<>();
	private float[] rowTop = new float[1];
	private List<ModrinthProject> layoutList;
	private int layoutVersion;
	private int layoutBuiltVersion = -1;
	private float prepShown;
	private volatile Map<String, ConfigInfo> configInfo = Collections.emptyMap();
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
	private String initialStatus;
	private boolean devFake;
	private boolean devLeaveDone;
	private int[] devMouse;
	private long devNextShot;
	private long devStart;
	private int devStep;
	private int btnX;
	private int btnY;
	private int btnW;
	private int btnH;
	private int gearX;
	private int gearW;
	private int applyX;
	private int applyW;

	/** Что известно про настройки установленного мода. modId == null — мод ещё не загружен (игру не перезапускали). */
	private static final class ConfigInfo {
		final String modId;
		final boolean screen;
		final Path file;

		ConfigInfo(String modId, boolean screen, Path file) {
			this.modId = modId;
			this.screen = screen;
			this.file = file;
		}

		boolean usable() {
			return this.modId != null && (this.screen || this.file != null);
		}
	}

	public CatalogScreen(class_437 parent) {
		super(class_2561.method_43470("Моды Argentum"));
		this.parent = parent;
		this.gameVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.21.1");
	}

	/** Каталог после бесшовного применения модов: сразу вкладка «Установленные» и сообщение о результате. */
	public CatalogScreen afterApply(String message) {
		this.tab = Tab.INSTALLED;
		this.tabPos = 1F;
		this.initialStatus = message == null || message.isEmpty() ? "Моды применены" : message;

		return this;
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

		// виртуальная мышь для скриншотов без ввода: сначала над иконкой третьей карточки, потом над её кнопкой
		if (t > 28000 && t < 33500) {
			this.devMouse = new int[] {PAD + 5 + ICON - 6 + (int) ((t - 28000) / 400), LIST_TOP + 2 * (CARD_H + GAP) + 9};
		} else if (t >= 33500 && t < 40000) {
			this.devMouse = new int[] {this.field_22789 - PAD - SCROLLBAR_W - 3 - 40, LIST_TOP + 2 * (CARD_H + GAP) + BTN_Y + 5};
		} else {
			this.devMouse = null;
		}

		// имитация удаления первой установленной карточки (без удаления файлов), замедленная для снимков
		if (!this.devLeaveDone && this.tab == Tab.INSTALLED && t > 14300 && this.installedRows.size() > 1) {
			this.leaving.put(this.installedRows.get(0).id, 0F);
			this.layoutVersion++;
			this.devLeaveDone = true;
		}

		// имитация установки для скриншотов: полоса растёт от 0 до 100 % (без сети)
		if (t > 19000 && t < 27000 && !this.catalogRows.isEmpty()) {
			String id = this.catalogRows.get(0).id;
			float f = Math.min(1F, (t - 19000) / 7000F);
			this.busyIds.add(id);
			this.progressById.put(id, f);
			this.installFraction = f;
			this.devFake = true;
		} else if (this.devFake && t >= 27000) {
			this.busyIds.clear();
			this.progressById.clear();
			this.installFraction = -2F;
			this.devFake = false;
		}

		if (this.devStep == 0 && t > 3000 && this.catalogRows.size() > 2) {
			openDetail(this.catalogRows.get(2));
			this.devStep = 1;
		} else if (this.devStep == 1 && t > 8000) {
			switchTab(Tab.INSTALLED);
			this.devStep = 2;
		} else if (this.devStep == 2 && t > 11000 && this.installedRows.size() > 1) {
			openDetail(this.installedRows.get(1));
			this.devStep = 3;
		} else if (this.devStep == 3 && t > 15000) {
			closeDetail();
			this.devStep = 4;
		} else if (this.devStep == 4 && t > 16500) {
			switchTab(Tab.CATALOG);
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

		this.searchField = new class_342(this.field_22793, PAD + 5, SEARCH_Y + 7, w - PAD * 2 - FIELD_H - 4 - 10, 10, text(""));
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

			if (this.tab == Tab.INSTALLED) {
				loadInstalled();

				if (this.initialStatus != null) {
					setStatus(this.initialStatus, C_OK);
				}
			} else {
				search("");
			}
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

		Map<String, ConfigInfo> info = new HashMap<>();

		for (Map.Entry<String, String> e : files.entrySet()) {
			String modId = ModConfigs.modIdForFile(e.getValue());
			ModrinthProject known = this.cache.project(e.getKey());
			info.put(e.getKey(), new ConfigInfo(modId, modId != null && ModConfigs.hasScreen(modId),
					modId == null ? null : ModConfigs.configPath(modId, known == null ? null : known.slug)));
		}

		this.configInfo = info;
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
			this.listEpoch++;
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
					this.listEpoch++;
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
		this.listEpoch++;
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
		this.listEpoch++; // новый список плавно проявляется
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
		this.installFraction = -1F;
		this.progressById.put(p.id, -1F);

		WORKER.submit(() -> {
			boolean handedOver = false;

			try {
				InstalledModsIndex index = InstalledModsIndex.load(modsDir());
				List<ModrinthVersion> plan = DependencyResolver.resolve(p.id, LOADER, this.gameVersion, index);

				if (plan.isEmpty()) {
					setStatus("«" + p.title + "» уже установлен либо нет версии под " + this.gameVersion, C_RED);
					return;
				}

				ModInstaller.InstallResult result = ModInstaller.install(modsDir(), plan, (fileNo, count, name, done, total) -> {
					float fraction = total > 0 ? (fileNo + (float) done / total) / count : (float) fileNo / count;
					this.installFraction = fraction;
					this.progressById.put(p.id, fraction);
					setStatus("Скачиваю " + name + " (" + (fileNo + 1) + " из " + count + ")", C_GRAY);
				});
				this.installFraction = -1F; // дальше — подключение к игре, прогресс неизвестен
				this.progressById.put(p.id, -1F);
				this.cache.putProjects(Collections.singletonList(p)); // карточка нужна вкладке «Установленные» без сети
				this.cache.save();
				refreshInstalledIndex();

				String names = String.join(", ", result.installedModNames);
				setStatus("Установлено: " + names + ". Подключаю к запущенной игре...", C_GRAY);

				// hot-loader трогает классы игры и запускает инициализаторы модов — это делаем в потоке рендера
				handedOver = true;
				this.field_22787.execute(() -> hotLoad(result.installedFiles, names, p.id));
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Установка «" + p.title + "» не удалась", e);
				setStatus("Ошибка установки: " + e.getMessage() + (e.getCause() != null ? " (" + e.getCause() + ")" : ""), C_RED);
			} finally {
				if (!handedOver) {
					this.busyIds.remove(p.id);
					this.progressById.remove(p.id);
					this.installFraction = -2F;
				}
			}
		});
	}

	/** Подключает свежеустановленные моды без перезапуска там, где это безопасно; остальным честно пишет причину. */
	private void hotLoad(List<Path> files, String names, String busyId) {
		try {
			HotLoadReport report = HotLoader.load(files);
			refreshInstalledIndex();

			boolean failed = report.count(HotLoadReport.Status.FAILED) > 0;
			boolean restart = report.count(HotLoadReport.Status.NEEDS_RESTART) > 0;
			setStatus("Установлено: " + names + " — " + report.summary(), failed ? C_RED : restart ? C_WARN : C_OK);

			if (failed || restart) {
				Handoff.requestPrepare(); // то, что не подключилось на лету, готовим в фоновой копии игры
			}

			if (report.needsResourceReload()) {
				this.field_22787.method_1521(); // reloadResources: игра увидит текстуры и переводы новых модов
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.MODRINTH, "Hot-loader не справился", t);
			setStatus("Установлено: " + names + ". Перезапусти игру, чтобы мод заработал.", C_WARN);
		} finally {
			this.busyIds.remove(busyId);
			this.progressById.remove(busyId);
			this.installFraction = -2F;
		}
	}

	private void remove(ModrinthProject p) {
		String fileName = this.installedFiles.get(p.id);

		if (fileName == null || !this.busyIds.add(p.id)) {
			return;
		}

		boolean deferred = false;

		try {
			Files.deleteIfExists(modsDir().resolve(fileName));
		} catch (IOException e) {
			// Windows не даёт удалить jar, который игра сейчас держит открытым — удалим его при следующем запуске
			try {
				PendingRemovals.schedule(modsDir(), fileName);
				deferred = true;
			} catch (IOException e2) {
				setStatus("Не удалось удалить " + fileName + ": " + e2.getMessage(), C_RED);
				this.busyIds.remove(p.id);
				return;
			}
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
		this.leaving.put(p.id, 0F); // карточка плавно уходит; из списка её уберёт цикл анимации
		this.layoutVersion++;
		this.busyIds.remove(p.id);

		if (this.detail != null && this.detail.id.equals(p.id)) {
			closeDetail();
		}

		if (deferred) {
			Handoff.requestPrepare();
		}

		setStatus(deferred ? "«" + p.title + "» перестанет работать после перезапуска: сейчас файл занят игрой, он удалится при следующем запуске"
				: "Удалено: " + fileName, deferred ? C_WARN : C_OK);
	}

	/** Действие кнопки карточки/панели: поставить, либо удалить на вкладке установленных. */
	private void act(ModrinthProject p) {
		if (this.tab == Tab.INSTALLED) {
			remove(p);
		} else if (!this.installedFiles.containsKey(p.id)) {
			install(p);
		}
	}

	/** Настройки мода: его собственный экран (Mod Menu API), иначе файл из config/ в системной программе. */
	private void openConfig(ModrinthProject p) {
		ConfigInfo c = this.configInfo.get(p.id);

		if (c == null || c.modId == null) {
			setStatus("«" + p.title + "» ещё не загружен — перезапусти игру, и настройки станут доступны", C_GRAY);
			return;
		}

		if (c.screen) {
			class_437 screen = ModConfigs.screen(c.modId, this);

			if (screen != null) {
				this.field_22787.method_1507(screen);
				return;
			}
		}

		if (c.file != null) {
			Path file = c.file;
			setStatus("Открываю настройки: " + file.getFileName(), C_OK);

			WORKER.submit(() -> {
				try {
					ModConfigs.openExternally(file);
				} catch (IOException e) {
					Log.warn(LogCategory.MODRINTH, "Не удалось открыть " + file, e);
					setStatus("Не удалось открыть " + file.getFileName() + ": " + e.getMessage(), C_RED);
				}
			});

			return;
		}

		setStatus("У «" + p.title + "» нет настроек, которые можно открыть отсюда", C_GRAY);
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
		return w - (int) ((PANEL_W + PAD) * this.panelAnim);
	}

	/** Правая граница списка: панель описания не перекрывает карточки, а сдвигает их. */
	private int listRight() {
		return panelX(this.field_22789);
	}

	private boolean overPanel(double mx, double my) {
		int w = this.field_22789;

		return this.panelAnim > 0.4F && inside(mx, my, panelX(w), LIST_TOP + 4, PANEL_W, listBottom() - LIST_TOP - 8);
	}

	// ---------------------------------------------------------------- раскладка

	private int cardWidth() {
		return listRight() - PAD * 2 - SCROLLBAR_W - 3;
	}

	/** Ширина правой колонки карточки (статистика + кнопки); в узком списке — уже. */
	private int rightCol(int cardW) {
		return cardW >= 300 ? RIGHT_COL : 84;
	}

	/** Слева от кнопки «Удалить» на вкладке установленных стоит кнопка настроек. */
	private int actionOffset() {
		return this.tab == Tab.INSTALLED ? GEAR_W + 4 : 0;
	}

	private int listBottom() {
		return this.field_22790 - STATUS_H;
	}

	/** Плавность ухода карточки: быстро в начале, мягко в конце. */
	private static float easeOut(float t) {
		float u = 1F - Math.max(0F, Math.min(1F, t));

		return 1F - u * u;
	}

	/**
	 * Раскладка строк: верх каждой карточки относительно начала списка. Карточка, которую удаляют, постепенно
	 * «схлопывается», и все ниже плавно поднимаются.
	 */
	private void ensureLayout() {
		List<ModrinthProject> list = rows();

		if (list == this.layoutList && this.layoutVersion == this.layoutBuiltVersion && this.rowTop.length == list.size() + 1) {
			return;
		}

		float[] top = new float[list.size() + 1];
		float cum = 0F;

		for (int i = 0; i < list.size(); i++) {
			top[i] = cum;
			Float f = this.leaving.get(list.get(i).id);
			cum += (CARD_H + GAP) * (f == null ? 1F : 1F - easeOut(f));
		}

		top[list.size()] = cum;
		this.rowTop = top;
		this.layoutList = list;
		this.layoutBuiltVersion = this.layoutVersion;
	}

	/** Индекс строки, в которую попадает вертикальная координата внутри списка (с учётом прокрутки); -1 — нет. */
	private int rowAt(float yInList) {
		ensureLayout();

		for (int i = 0; i + 1 < this.rowTop.length; i++) {
			if (yInList >= this.rowTop[i] && yInList < this.rowTop[i + 1]) {
				return i;
			}
		}

		return -1;
	}

	private float contentHeight() {
		ensureLayout();
		int n = rows().size();

		return n == 0 ? 0 : Math.max(0F, this.rowTop[n] - GAP);
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

			if (inside(mx, my, px + PANEL_W - 8 - 16, LIST_TOP + 4 + 5, 16, 16)) {
				press("close");
				closeDetail();
			} else if (this.detail != null && inside(mx, my, this.btnX, this.btnY, this.btnW, this.btnH)) {
				press("panel-act");
				act(this.detail);
			} else if (this.detail != null && this.tab == Tab.INSTALLED && inside(mx, my, this.gearX, this.btnY, this.gearW, this.btnH)) {
				press("panel-gear");
				openConfig(this.detail);
			}

			return true;
		}

		// лупа: поиск сразу, без ожидания
		if (inside(mx, my, w - PAD - FIELD_H, SEARCH_Y, FIELD_H, FIELD_H)) {
			press("lupa");

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

		if (this.applyW > 0 && inside(mx, my, this.applyX, h - STATUS_H + 3, this.applyW, BTN_H)) {
			Handoff.State hs = Handoff.state();

			if (hs == Handoff.State.READY) {
				press("apply");
				Handoff.swap(this.field_22787, true, "Моды применены: " + pendingNames());
			} else if (hs == Handoff.State.FAILED) {
				press("apply");
				Handoff.requestPrepare();
			}

			return true;
		}

		if (inside(mx, my, w - PAD - 52, h - STATUS_H + 3, 52, BTN_H)) {
			press("back");
			this.method_25419();
			return true;
		}

		if (my >= LIST_TOP && my < listBottom()) {
			int index = rowAt((float) (my - LIST_TOP + this.scroll));
			List<ModrinthProject> current = rows();

			if (index >= 0 && index < current.size()) {
				int cardY = (int) (LIST_TOP + this.rowTop[index] - this.scroll);
				ModrinthProject p = current.get(index);

				if (this.leaving.containsKey(p.id)) {
					return true; // карточка уже уходит
				}

				int cw = cardWidth();

				if (mx < PAD || mx >= PAD + cw) {
					return false;
				}

				int col = rightCol(cw);
				int colX = PAD + cw - col - 5;
				int off = actionOffset();

				if (off > 0 && inside(mx, my, colX, cardY + BTN_Y, GEAR_W, BTN_H)) {
					press("gear:" + p.id);
					openConfig(p);
				} else if (inside(mx, my, colX + off, cardY + BTN_Y, col - off, BTN_H)) {
					press("act:" + p.id);
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

	private static int lighter(int c, float t) {
		return lerpColor(c, C_WHITE, t);
	}

	private static int darker(int c, float t) {
		return lerpColor(c, 0xFF000000, t);
	}

	/**
	 * Рамка в духе Ore UI: чёрный контур в 1 px, чуть более светлая обводка (1 px) и тёмная нижняя полоса в 2 px.
	 * При нажатии нижняя полоса «проваливается».
	 */
	private static void frame(class_332 ctx, int x, int y, int w, int h, int base, int borderColor, int bottomColor, boolean pressed) {
		ctx.method_25294(x, y, x + w, y + h, C_BLACK);
		ctx.method_25294(x + 1, y + 1, x + w - 1, y + h - 1, base);

		int strip = pressed ? 0 : 2;

		if (strip > 0) {
			ctx.method_25294(x + 1, y + h - 1 - strip, x + w - 1, y + h - 1, bottomColor);
		}

		int regionBottom = y + h - 1 - strip;
		ctx.method_25294(x + 1, y + 1, x + w - 1, y + 2, borderColor);
		ctx.method_25294(x + 1, regionBottom - 1, x + w - 1, regionBottom, borderColor);
		ctx.method_25294(x + 1, y + 1, x + 2, regionBottom, borderColor);
		ctx.method_25294(x + w - 2, y + 1, x + w - 1, regionBottom, borderColor);
	}

	/** Плашка-тег: заливка и тёмная линия снизу. */
	private static void chip(class_332 ctx, int x, int y, int w, int h) {
		ctx.method_25294(x, y, x + w, y + h, C_CHIP);
		ctx.method_25294(x, y + h - 1, x + w, y + h, darker(C_CHIP, 0.3F));
	}

	/** Полоса прогресса: чёрный контур, тёмный жёлоб и акцентная заливка; frac < 0 — бегущий отрезок. */
	private void progressBar(class_332 ctx, int x, int y, int w, int h, float frac) {
		ctx.method_25294(x, y, x + w, y + h, C_BLACK);
		ctx.method_25294(x + 1, y + 1, x + w - 1, y + h - 1, C_FIELD);

		int innerW = w - 2;
		int from;
		int to;

		if (frac >= 0F) {
			from = 0;
			to = Math.round(innerW * Math.min(1F, frac));
		} else {
			float phase = (System.currentTimeMillis() % 1300) / 1300F;
			int seg = Math.max(8, innerW * 3 / 10);
			from = (int) (-seg + (innerW + seg) * phase);
			to = from + seg;
			from = Math.max(0, from);
			to = Math.min(innerW, to);
		}

		if (to > from) {
			ctx.method_25294(x + 1 + from, y + 1, x + 1 + to, y + h - 1, C_ACCENT);
			ctx.method_25294(x + 1 + from, y + 1, x + 1 + to, y + 2, lighter(C_ACCENT, 0.3F));
			ctx.method_25294(x + 1 + from, y + h - 2, x + 1 + to, y + h - 1, darker(C_ACCENT, 0.4F));
		}
	}

	/** Вспышка кнопки при нажатии (сама затухает). */
	private void press(String key) {
		this.flash.put(key, 1F);
	}

	/** Плавное наведение: 0..1, для каждой кнопки своё состояние по ключу. */
	private float anim(String key, boolean on) {
		float v = approach(this.btnHover.getOrDefault(key, 0F), on ? 1F : 0F, this.dt, 16);
		this.btnHover.put(key, v);

		return v;
	}

	/**
	 * Кнопка в стиле Ore UI: цвет плавно перетекает при наведении, при нажатии рамка «вдавливается».
	 * Слева может стоять иконка или крутящийся спиннер (кнопка занята).
	 */
	private void button(class_332 ctx, String key, int x, int y, int w, int h, String label, String[] icon, boolean spin,
			int base, int hoverColor, boolean hovered, int textColor) {
		buttonFill(ctx, key, x, y, w, h, label, icon, spin, base, hoverColor, hovered, textColor, -1F);
	}

	/** То же, что {@link #button}, но с заливкой-прогрессом внутри (fill 0..1; меньше нуля — без заливки). */
	private void buttonFill(class_332 ctx, String key, int x, int y, int w, int h, String label, String[] icon, boolean spin,
			int base, int hoverColor, boolean hovered, int textColor, float fill) {
		float k = anim(key, hovered);
		boolean pressed = this.flash.getOrDefault(key, 0F) > 0.55F;
		int body = lerpColor(base, hoverColor, k);
		boolean accent = base == C_ACCENT;
		int borderColor = lighter(body, accent ? 0.26F : 0.08F + 0.1F * k);
		int bottomColor = darker(body, accent ? 0.55F : 0.28F);
		frame(ctx, x, y, w, h, body, borderColor, bottomColor, pressed);

		if (fill >= 0F) {
			int fw = Math.round((w - 4) * Math.min(1F, fill));

			if (fw > 0) {
				int bottom = y + h - (pressed ? 3 : 5);
				ctx.method_25294(x + 2, y + 2, x + 2 + fw, bottom, C_ACCENT);
				ctx.method_25294(x + 2, y + 2, x + 2 + fw, y + 3, lighter(C_ACCENT, 0.3F));
			}
		}

		int lead = spin ? 11 : icon != null ? PixelIcons.width(icon) : 0;
		String s = label == null ? "" : fit(label, w - 8 - (lead > 0 ? lead + 4 : 0));
		int textW = textWidth(s);
		int total = lead + (lead > 0 && !s.isEmpty() ? 4 : 0) + textW;
		int cx = x + (w - total) / 2;
		int shift = pressed ? 1 : 0;
		int midY = y + (h - 2) / 2 + shift; // центр области над нижней полосой

		if (spin) {
			PixelIcons.spinner(ctx, cx, midY - 5, textColor);
		} else if (icon != null) {
			PixelIcons.draw(ctx, icon, cx, midY - PixelIcons.height(icon) / 2, textColor);
		}

		if (!s.isEmpty()) {
			text(ctx, s, cx + total - textW, midY - 4, textColor);
		}
	}

	private static float approach(float current, float target, float dt, float speed) {
		float next = current + (target - current) * (1 - (float) Math.exp(-dt * speed));

		return Math.abs(target - next) < 0.004F ? target : next;
	}

	private boolean busyNow() {
		Handoff.State hs = Handoff.state();

		return this.loading || this.loadingMore || !this.busyIds.isEmpty() || hs == Handoff.State.PREPARING || hs == Handoff.State.SWAPPING;
	}

	@Override
	public void method_25394(class_332 ctx, int mx, int my, float delta) {
		super.method_25394(ctx, mx, my, delta);

		if (this.devTimeline) {
			devShot();
		}
	}

	/** Dev: сохраняет кадр игры в .argentum/shots каждые 2,5 с (без участия окна ОС и без ввода). */
	private void devShot() {
		long t = this.devStart == 0 ? 0 : System.currentTimeMillis() - this.devStart;

		if (t < this.devNextShot || t > 80_000) {
			return;
		}

		this.devNextShot = t + 2500;

		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve(".argentum").resolve("shots");
			Files.createDirectories(dir);

			try (net.minecraft.class_1011 image = net.minecraft.class_318.method_1663(this.field_22787.method_1522())) {
				image.method_4314(dir.resolve(String.format("s%03d.png", t / 1000)));
			}
		} catch (IOException | RuntimeException e) {
			Log.warn(LogCategory.MODRINTH, "dev: не удалось сохранить кадр", e);
		}
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

		if (this.devMouse != null) {
			mx = this.devMouse[0];
			my = this.devMouse[1];
		}

		// живой поиск: запрос уходит, когда ввод затих
		if (this.lastEditMs > 0 && this.tab == Tab.CATALOG && System.currentTimeMillis() - this.lastEditMs > SEARCH_DEBOUNCE_MS) {
			this.lastEditMs = -1;
			search(this.query.trim());
		}

		// плавность: прокрутка, вкладки, панель, фокус поля, полоска загрузки
		this.dt = dt;
		this.scrollTarget = Math.max(0, Math.min(maxScroll(), this.scrollTarget));
		this.scroll = approach(this.scroll, this.scrollTarget, dt, 16);
		this.tabPos = approach(this.tabPos, this.tab == Tab.INSTALLED ? 1F : 0F, dt, 14);
		this.panelAnim = approach(this.panelAnim, this.panelOpen ? 1F : 0F, dt, 13);
		this.detailScrollTarget = Math.max(0, this.detailScrollTarget);
		this.detailScroll = approach(this.detailScroll, this.detailScrollTarget, dt, 16);
		this.focusAnim = approach(this.focusAnim, this.searchField.method_25370() ? 1F : 0F, dt, 12);
		this.loadAnim = approach(this.loadAnim, busyNow() ? 1F : 0F, dt, 8);
		if (!this.leaving.isEmpty()) {
			boolean finished = false;

			for (Map.Entry<String, Float> e : this.leaving.entrySet()) {
				e.setValue(Math.min(1F, e.getValue() + dt * (this.devTimeline ? 0.8F : 3.2F)));
				finished |= e.getValue() >= 1F;
			}

			this.layoutVersion++;

			if (finished) {
				List<ModrinthProject> left = new ArrayList<>(this.installedRows);
				left.removeIf(x -> this.leaving.getOrDefault(x.id, 0F) >= 1F);
				this.installedRows = left;
				this.leaving.values().removeIf(v -> v >= 1F);
			}
		}

		this.installShown = this.installFraction >= 0F ? approach(this.installShown, this.installFraction, dt, 10) : 0F;
		this.prepShown = Handoff.state() == Handoff.State.PREPARING ? approach(this.prepShown, Handoff.progress(), dt, 8) : 0F;

		if (this.appearEpoch != this.listEpoch) { // список заменили — карточки появляются заново
			this.appearEpoch = this.listEpoch;
			this.appear.clear();
		}

		this.flash.replaceAll((k, v) -> v - dt * 4F);
		this.flash.values().removeIf(v -> v <= 0F);

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
		ctx.method_25294(0, LIST_TOP - 2, w, LIST_TOP - 1, C_BLACK);

		// полоска под шапкой: ход установки или подготовки модов, а если прогресс неизвестен — бегущий отрезок
		float line = -1F;
		Handoff.State hs = Handoff.state();

		if (this.installFraction >= 0F) {
			line = this.installShown;
		} else if (this.installFraction < -1.5F && hs == Handoff.State.PREPARING) {
			line = this.prepShown;
		}

		if (this.loadAnim > 0.01F) {
			int color = ((int) (this.loadAnim * 255) << 24) | (C_ACCENT & 0x00FFFFFF);

			if (line >= 0F) {
				ctx.method_25294(0, LIST_TOP - 3, (int) (w * line), LIST_TOP - 1, color);
			} else {
				float phase = (System.currentTimeMillis() % 1500) / 1500F;
				float eased = phase * phase * (3 - 2 * phase);
				int segW = w * 3 / 10;
				int x0 = (int) (-segW + (w + segW) * eased);
				ctx.method_25294(Math.max(0, x0), LIST_TOP - 3, Math.min(w, x0 + segW), LIST_TOP - 1, color);
			}
		}

		// поле поиска (текст рисует виджет поверх): обводка плавно становится акцентной при фокусе
		int fieldW = w - PAD * 2 - FIELD_H - 4;
		frame(ctx, PAD, SEARCH_Y, fieldW, FIELD_H, C_FIELD, lerpColor(lighter(C_FIELD, 0.14F), 0xFF6A6B6D, this.focusAnim), darker(C_FIELD, 0.4F), false);

		// кнопка-лупа
		int lx = w - PAD - FIELD_H;
		button(ctx, "lupa", lx, SEARCH_Y, FIELD_H, FIELD_H, null, PixelIcons.SEARCH, false, C_SECONDARY, C_SECONDARY_HOVER, inside(mx, my, lx, SEARCH_Y, FIELD_H, FIELD_H), C_WHITE);

		// вкладки: подчёркивание плавно переезжает, подписи плавно меняют цвет
		for (Tab t : Tab.values()) {
			float weight = t == Tab.CATALOG ? 1F - this.tabPos : this.tabPos;
			boolean hovered = inside(mx, my, tabX(t), TABS_Y, tabWidth(t), TABS_H);
			String label = t == Tab.CATALOG ? "Каталог" : "Установленные";
			int color = lerpColor(lerpColor(C_GRAY, C_TEXT, anim("tab" + t, hovered)), C_WHITE, weight);
			text(ctx, label, tabX(t) + (tabWidth(t) - textWidth(label)) / 2, TABS_Y + 4, color);
		}

		int ux = (int) (tabX(Tab.CATALOG) + (tabX(Tab.INSTALLED) - tabX(Tab.CATALOG)) * this.tabPos);
		int uw = (int) (tabWidth(Tab.CATALOG) + (tabWidth(Tab.INSTALLED) - tabWidth(Tab.CATALOG)) * this.tabPos);
		ctx.method_25294(ux, TABS_Y + TABS_H - 2, ux + uw, TABS_Y + TABS_H, C_ACCENT);
	}

	private void drawList(class_332 ctx, int mx, int my, float dt, int w) {
		List<ModrinthProject> current = rows();
		int bottom = listBottom();
		int right = listRight();

		if (current.isEmpty()) {
			if (this.loading) {
				ctx.method_44379(0, LIST_TOP, right, bottom);
				drawSkeleton(ctx);
				ctx.method_44380();
			} else {
				String message = this.tab == Tab.CATALOG ? "Ничего не найдено" : "Здесь появятся моды, установленные через каталог";
				text(ctx, message, (right - textWidth(message)) / 2, LIST_TOP + (bottom - LIST_TOP) / 2 - 4, C_GRAY);
			}

			return;
		}

		int cardW = cardWidth();

		ctx.method_44379(0, LIST_TOP, right, bottom); // enableScissor

		ensureLayout();
		int first = Math.max(0, rowAt(this.scroll));
		int fresh = 0;

		for (int i = first; i < current.size(); i++) {
			int y = (int) (LIST_TOP + this.rowTop[i] - this.scroll);

			if (y >= bottom) {
				break;
			}

			ModrinthProject p = current.get(i);
			Float leave = this.leaving.get(p.id);

			if (leave != null) {
				// уходящая карточка: обрезается снизу и растворяется в фон
				float e = easeOut(leave);
				int visibleH = Math.max(0, (int) (CARD_H * (1F - e)));

				if (visibleH > 0) {
					ctx.method_44379(0, Math.max(y, LIST_TOP), right, Math.min(y + visibleH, bottom));
					drawCard(ctx, p, PAD, y, cardW, -1000, -1000, dt);
					ctx.method_25294(0, y - 1, right, y + CARD_H + 1, (((int) (e * 255)) << 24) | (C_BG & 0x00FFFFFF));
					ctx.method_44380();
				}

				continue;
			}

			// появление: прозрачность + лёгкий сдвиг снизу, карточки идут друг за другом с небольшой задержкой
			Float known = this.appear.get(p.id);
			float a;

			if (known == null) {
				a = -Math.min(fresh++, 6) * 0.12F;
			} else {
				a = known;
			}

			a = Math.min(1F, a + dt * 4F);
			this.appear.put(p.id, a);

			if (a <= 0F) {
				continue;
			}

			int dy = a >= 1F ? 0 : (int) ((1F - a) * 6);
			drawCard(ctx, p, PAD, y + dy, cardW, mx, my, dt);

			if (a < 1F) {
				ctx.method_25294(0, y + dy - 1, right, y + dy + CARD_H + 1, (((int) ((1F - a) * 255)) << 24) | (C_BG & 0x00FFFFFF));
			}
		}

		// индикатор подгрузки внизу списка
		if (this.loadingMore) {
			int y = (int) (LIST_TOP + this.rowTop[current.size()] - this.scroll);

			if (y < bottom) {
				String more = "Загружаю ещё";
				int total = 11 + 6 + textWidth(more);
				int sx = (right - total) / 2;
				PixelIcons.spinner(ctx, sx, y + 2, C_ACCENT);
				text(ctx, more, sx + 17, y + 4, C_GRAY);
			}
		}

		ctx.method_44380(); // disableScissor

		// полоса прокрутки
		float max = maxScroll();

		if (max > 0) {
			int trackH = bottom - LIST_TOP;
			int thumbH = Math.max(14, (int) (trackH * (trackH / contentHeight())));
			int thumbY = LIST_TOP + (int) ((trackH - thumbH) * (this.scroll / max));
			int sx = right - PAD - SCROLLBAR_W;
			ctx.method_25294(sx, LIST_TOP, sx + SCROLLBAR_W, bottom, C_BLACK);
			ctx.method_25294(sx + 1, thumbY + 1, sx + SCROLLBAR_W - 1, thumbY + thumbH - 1, C_SECONDARY_HOVER);
			ctx.method_25294(sx + 1, thumbY + 1, sx + SCROLLBAR_W - 1, thumbY + 2, lighter(C_SECONDARY_HOVER, 0.15F));
		}
	}

	/** Заглушки-карточки, пока список ещё не пришёл: серые блоки мягко пульсируют. */
	private void drawSkeleton(class_332 ctx) {
		int cardW = cardWidth();
		int col = rightCol(cardW);
		float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 330.0);
		int bar = lerpColor(C_CHIP, C_PANEL_HOVER, pulse);
		int bottom = listBottom();

		for (int i = 0; ; i++) {
			int y = LIST_TOP + i * (CARD_H + GAP);

			if (y >= bottom) {
				break;
			}

			int x = PAD;
			int textX = x + 4 + ICON + 8;
			int rightX = x + cardW - col - 4;
			int textEnd = rightX - 6;

			frame(ctx, x, y, cardW, CARD_H, C_PANEL, lighter(C_PANEL, 0.07F), darker(C_PANEL, 0.3F), false);
			ctx.method_25294(x + 5, y + 8, x + 5 + ICON, y + 8 + ICON, bar);
			ctx.method_25294(textX, y + 8, Math.min(textEnd, textX + 90 + (i % 3) * 28), y + 16, bar);
			ctx.method_25294(textX, y + 21, Math.min(textEnd, textX + 150 + (i % 4) * 22), y + 29, bar);
			ctx.method_25294(textX, y + 35, Math.min(textEnd, textX + 38), y + 46, bar);
			ctx.method_25294(Math.min(textEnd, textX + 42), y + 35, Math.min(textEnd, textX + 90), y + 46, bar);
			ctx.method_25294(rightX, y + 8, rightX + col * 2 / 3, y + 15, bar);
			ctx.method_25294(rightX, y + BTN_Y, rightX + col, y + BTN_Y + BTN_H, bar);
		}
	}

	private void drawCard(class_332 ctx, ModrinthProject p, int x, int y, int w, int mx, int my, float dt) {
		boolean hovered = inside(mx, my, x, y, w, CARD_H) && my >= LIST_TOP && my < listBottom();
		boolean selected = this.panelOpen && this.detail != null && this.detail.id.equals(p.id);
		float h = this.hover.getOrDefault(p.id, 0F);
		h = approach(h, hovered || selected ? 1F : 0F, dt, 14);
		this.hover.put(p.id, h);

		int base = lerpColor(C_PANEL, C_PANEL_HOVER, h);
		int borderColor = selected ? lerpColor(lighter(base, 0.08F), C_ACCENT, h) : lighter(base, 0.07F + 0.08F * h);
		frame(ctx, x, y, w, CARD_H, base, borderColor, darker(base, 0.3F), false);

		// иконка разворачивается к курсору
		float[] t = this.tilt.computeIfAbsent(p.id, k -> new float[2]);
		float targetY = 0F;
		float targetX = 0F;

		if (hovered) {
			targetY = clamp((mx - (x + 5 + ICON / 2F)) / 90F) * TILT_MAX;
			targetX = clamp((my - (y + 8 + ICON / 2F)) / 40F) * TILT_MAX;
		}

		t[0] = approach(t[0], targetY, dt, 14);
		t[1] = approach(t[1], targetX, dt, 14);
		drawIcon(ctx, p, x + 5, y + 8, ICON, t[0], t[1]);

		int col = rightCol(w);
		int textX = x + 5 + ICON + 8;
		int rightX = x + w - col - 5;
		int textW = rightX - textX - 6;

		// название + «by автор»
		String title = fit(p.title, textW);
		text(ctx, title, textX, y + 8, C_WHITE);

		if (p.author != null) {
			text(ctx, fit("by " + p.author, textW - textWidth(title) - 4), textX + textWidth(title) + 4, y + 8, C_GRAY);
		}

		// описание — как на Modrinth, одной строкой с многоточием
		text(ctx, fit(p.description, textW), textX, y + 20, C_TEXT);

		// теги (на вкладке установленных первым идёт статус: работает сейчас или после перезапуска)
		int chipX = textX + drawBadge(ctx, p, textX, y + 35, textW);

		for (String tag : Fmt.tags(p)) {
			int chipW = textWidth(tag) + 8;

			if (chipX + chipW > textX + textW) {
				break;
			}

			chip(ctx, chipX, y + 35, chipW, 11);
			text(ctx, tag, chipX + 4, y + 37, C_GRAY);
			chipX += chipW + 4;
		}

		// правая колонка: скачивания, лайки, обновление, кнопки
		if (!noStats(p)) {
			drawStats(ctx, p, rightX, y + 8, col);
		}

		int off = actionOffset();

		if (off > 0) {
			drawGearButton(ctx, "gear:" + p.id, rightX, y + BTN_Y, GEAR_W, BTN_H, p, null, mx, my);
		}

		drawActionButton(ctx, "act:" + p.id, p, rightX + off, y + BTN_Y, col - off, BTN_H, mx, my);
	}

	private static float clamp(float v) {
		return Math.max(-1F, Math.min(1F, v));
	}

	/** Статус установленного мода: «Активен» (мод уже загружен в игру) или «После перезапуска». Возвращает занятую ширину. */
	private int drawBadge(class_332 ctx, ModrinthProject p, int x, int y, int maxWidth) {
		if (this.tab != Tab.INSTALLED) {
			return 0;
		}

		ConfigInfo c = this.configInfo.get(p.id);
		boolean active = c != null && c.modId != null;
		String label = active ? "Активен" : "После перезапуска";
		int chipW = textWidth(label) + 8;

		if (chipW > maxWidth) {
			return 0;
		}

		chip(ctx, x, y, chipW, 11);
		text(ctx, label, x + 4, y + 2, active ? C_OK : C_WARN);

		return chipW + 4;
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

	private void drawActionButton(class_332 ctx, String key, ModrinthProject p, int x, int y, int w, int h, int mx, int my) {
		boolean hovered = inside(mx, my, x, y, w, h);
		boolean busy = this.busyIds.contains(p.id);

		if (this.tab == Tab.INSTALLED) {
			button(ctx, key, x, y, w, h, busy ? "Удаляю" : "Удалить", null, busy, busy ? C_DISABLED : C_SECONDARY, C_SECONDARY_HOVER, hovered && !busy, busy ? C_GRAY : C_WHITE);
		} else if (this.installedFiles.containsKey(p.id) && !busy) {
			button(ctx, key, x, y, w, h, "Установлен", null, false, C_DISABLED, C_DISABLED, false, C_GRAY);
		} else if (busy) {
			Float target = this.progressById.get(p.id);
			float fl = target == null ? -1F : target;
			float shown = approach(this.btnHover.getOrDefault("pf:" + p.id, 0F), Math.max(0F, fl), this.dt, 10);
			this.btnHover.put("pf:" + p.id, shown);
			buttonFill(ctx, key, x, y, w, h, fl >= 0F ? Math.round(shown * 100) + "%" : "Установка", null, true, C_DISABLED, C_DISABLED, false, C_WHITE, fl >= 0F ? shown : -1F);
		} else {
			button(ctx, key, x, y, w, h, "Установить", null, false, C_ACCENT, C_ACCENT_HOVER, hovered, C_ACCENT_TEXT);
		}
	}

	/** Кнопка настроек мода (шестерёнка, с подписью или без). Серая, если открыть нечего или мод ещё не загружен. */
	private void drawGearButton(class_332 ctx, String key, int x, int y, int w, int h, ModrinthProject p, String label, int mx, int my) {
		ConfigInfo c = this.configInfo.get(p.id);
		boolean usable = c != null && c.usable();
		boolean hovered = inside(mx, my, x, y, w, h);

		button(ctx, key, x, y, w, h, label, PixelIcons.GEAR, false, usable ? C_SECONDARY : C_DISABLED, usable ? C_SECONDARY_HOVER : C_DISABLED,
				hovered && usable, usable ? (hovered ? C_OK : C_WHITE) : 0xFF5A5A64);
	}

	private void drawIcon(class_332 ctx, ModrinthProject p, int x, int y, int size) {
		drawIcon(ctx, p, x, y, size, 0F, 0F);
	}

	private void drawIcon(class_332 ctx, ModrinthProject p, int x, int y, int size, float ay, float bx) {
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

		if (Math.abs(ay) > 0.004F || Math.abs(bx) > 0.004F) {
			Icon3D.draw(ctx, icon.texture, x, y, size, ay, bx, alpha);
			RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

			return;
		}

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
		// DrawContext.drawTexture(Identifier, x, y, w, h, u, v, regionW, regionH, texW, texH)
		ctx.method_25293(icon.texture, x, y, size, size, 0F, 0F, icon.width, icon.height, icon.width, icon.height);
		RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
	}

	/** Выезжающая справа панель с описанием выбранного мода; список при этом сдвигается и сужается. */
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

		frame(ctx, px, py, PANEL_W, ph, C_HEADER, lighter(C_HEADER, 0.09F), darker(C_HEADER, 0.35F), false);
		ctx.method_25294(px + 1, py + 1, px + PANEL_W - 1, py + 2, C_ACCENT); // акцентная кромка сверху

		// шапка: иконка (разворачивается к курсору), название, автор, крестик
		boolean overIcon = inside(mx, my, innerX - 6, py + 2, 44, 40);
		float[] pt = this.tilt.computeIfAbsent("panel", k -> new float[2]);
		pt[0] = approach(pt[0], overIcon ? clamp((mx - (innerX + 14)) / 40F) * TILT_MAX : 0F, this.dt, 14);
		pt[1] = approach(pt[1], overIcon ? clamp((my - (py + 22)) / 28F) * TILT_MAX : 0F, this.dt, 14);
		drawIcon(ctx, p, innerX, py + 8, 28, pt[0], pt[1]);
		int titleX = innerX + 28 + 6;
		int titleW = innerW - 28 - 6 - 18;
		text(ctx, fit(p.title, titleW), titleX, py + 9, C_WHITE);

		if (p.author != null) {
			text(ctx, fit("by " + p.author, titleW), titleX, py + 21, C_GRAY);
		}

		int cx = px + PANEL_W - 8 - 16;
		button(ctx, "close", cx, py + 5, 16, 16, null, PixelIcons.CLOSE, false, C_SECONDARY, C_SECONDARY_HOVER, inside(mx, my, cx, py + 5, 16, 16), C_WHITE);

		int y = py + 42;

		if (!noStats(p)) {
			drawStats(ctx, p, innerX, y, innerW);
			y += 26;
		}

		// теги с переносом на следующие строки
		int chipX = innerX + drawBadge(ctx, p, innerX, y, innerW);

		for (String tag : Fmt.tags(p)) {
			int chipW = textWidth(tag) + 8;

			if (chipX + chipW > innerX + innerW) {
				chipX = innerX;
				y += 13;
			}

			chip(ctx, chipX, y, chipW, 11);
			text(ctx, tag, chipX + 4, y + 2, C_GRAY);
			chipX += chipW + 4;
		}

		y += 16;

		// кнопки (прямоугольники запоминаем для обработки клика): на вкладке установленных — «Настройки» и «Удалить» в ряд
		this.btnY = y;
		this.btnH = 20;

		if (this.tab == Tab.INSTALLED) {
			this.gearX = innerX;
			this.gearW = (innerW - 4) / 2;
			this.btnX = innerX + this.gearW + 4;
			this.btnW = innerW - this.gearW - 4;
			drawGearButton(ctx, "panel-gear", this.gearX, y, this.gearW, this.btnH, p, "Настройки", mx, my);
		} else {
			this.gearX = innerX;
			this.gearW = 0;
			this.btnX = innerX;
			this.btnW = innerW;
		}

		drawActionButton(ctx, "panel-act", p, this.btnX, this.btnY, this.btnW, this.btnH, mx, my);
		y += this.btnH + 8;

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

		int shortCount = shortLineCount(p, textW);

		for (int i = 0; i < lines.size(); i++) {
			int ly = (int) (textTop + i * LINE_H - this.detailScroll);

			if (ly + LINE_H < textTop) {
				continue;
			}

			if (ly > textBottom) {
				break;
			}

			text(ctx, lines.get(i), innerX, ly, i < shortCount ? C_TEXT : C_GRAY);
		}

		// описание ещё грузится — спиннер под последней строкой
		if (this.detailBodyState == 1) {
			int ly = (int) (textTop + (lines.size() - 1) * LINE_H - this.detailScroll);
			PixelIcons.spinner(ctx, innerX, ly - 1, C_ACCENT);
		}

		ctx.method_44380();

		if (maxDetailScroll > 0) {
			int trackH = textBottom - textTop;
			int thumbH = Math.max(10, (int) (trackH * (trackH / (float) contentH)));
			int thumbY = textTop + (int) ((trackH - thumbH) * (this.detailScroll / maxDetailScroll));
			int sx = innerX + innerW - SCROLLBAR_W;
			ctx.method_25294(sx, textTop, sx + SCROLLBAR_W, textBottom, C_BLACK);
			ctx.method_25294(sx + 1, thumbY + 1, sx + SCROLLBAR_W - 1, thumbY + thumbH - 1, C_SECONDARY_HOVER);
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
				lines.add("    Загружаю описание"); // слева место под спиннер
			}

			this.detailLines = lines;
			this.detailLinesKey = key;
		}

		return this.detailLines;
	}

	private String pendingNames() {
		int n = this.installedFiles.size();

		return n == 0 ? "изменений нет" : "установлено через каталог: " + n;
	}

	private void drawStatusBar(class_332 ctx, int mx, int my, int w, int h) {
		int top = h - STATUS_H;
		ctx.method_25294(0, top, w, h, C_HEADER);
		ctx.method_25294(0, top, w, top + 1, C_BLACK);

		int backW = 52;
		int rightEdge = w - PAD - backW - 4;
		int by = top + 3;

		// «Подготовка модов» / «Применить» слева от «Назад»; во время подготовки это полоса прогресса
		Handoff.State hs = Handoff.state();
		this.applyW = 0;

		if (hs != Handoff.State.IDLE) {
			String label;
			boolean clickable = false;
			boolean spin = false;
			int base = C_DISABLED;
			int hover = C_DISABLED;
			int textColor = C_GRAY;
			float fill = -1F;

			switch (hs) {
			case PREPARING:
				label = "Подготовка модов " + Math.round(this.prepShown * 100) + "%";
				spin = true;
				fill = this.prepShown;
				textColor = C_WHITE;
				break;
			case READY:
				label = "Применить моды";
				clickable = true;
				base = C_ACCENT;
				hover = C_ACCENT_HOVER;
				textColor = C_ACCENT_TEXT;
				break;
			case SWAPPING:
				label = "Переключаю";
				spin = true;
				break;
			default:
				label = "Не вышло — повторить";
				clickable = true;
				base = C_SECONDARY;
				hover = C_SECONDARY_HOVER;
				textColor = C_WARN;
			}

			int bw = Math.max(110, Math.min(170, textWidth(label) + (spin ? 11 + 4 : 0) + 18));
			this.applyW = bw;
			this.applyX = rightEdge - bw;
			boolean hovered = clickable && inside(mx, my, this.applyX, by, bw, BTN_H);
			buttonFill(ctx, "apply", this.applyX, by, bw, BTN_H, label, null, spin, base, hover, hovered, textColor, fill);
			rightEdge = this.applyX - 6;
		}

		// ход установки мода: полоса и проценты
		if (this.installFraction > -1.5F) {
			int barW = w < 400 ? 56 : 90;
			String percent = this.installFraction >= 0F ? Math.round(this.installShown * 100) + "%" : "";
			int percentW = Math.max(textWidth("100%"), textWidth(percent));
			int barX = rightEdge - percentW - 6 - barW;
			progressBar(ctx, barX, top + 8, barW, 8, this.installFraction >= 0F ? this.installShown : -1F);

			if (!percent.isEmpty()) {
				text(ctx, percent, rightEdge - percentW, top + 8, C_TEXT);
			}

			rightEdge = barX - 8;
		}

		int textX = PAD;

		if (busyNow()) {
			PixelIcons.spinner(ctx, PAD, top + (STATUS_H - 11) / 2, C_ACCENT);
			textX = PAD + 17;
		}

		String status = this.status;

		if (hs == Handoff.State.FAILED && status.isEmpty()) {
			status = "Моды не подготовлены: " + Handoff.failure();
		}

		text(ctx, fit(status, rightEdge - textX - 4), textX, top + 8, this.statusColor);

		boolean backHover = inside(mx, my, w - PAD - backW, by, backW, BTN_H);
		button(ctx, "back", w - PAD - backW, by, backW, BTN_H, "Назад", null, false, C_SECONDARY, C_SECONDARY_HOVER, backHover, C_TEXT);
	}
}
