package com.ganichgg.argentum.client.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import net.fabricmc.loader.impl.modrinth.CrashDiagnoser;
import net.fabricmc.loader.impl.modrinth.InstalledModsIndex;
import net.fabricmc.loader.impl.modrinth.LocalMods;
import net.fabricmc.loader.impl.modrinth.ModInstaller;
import net.fabricmc.loader.impl.modrinth.ModUpdates;
import net.fabricmc.loader.impl.modrinth.ModrinthCache;
import net.fabricmc.loader.impl.modrinth.ModrinthClient;
import net.fabricmc.loader.impl.modrinth.ModrinthProject;
import net.fabricmc.loader.impl.modrinth.ModrinthVersion;
import net.fabricmc.loader.impl.modrinth.MrpackImporter;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import net.minecraft.class_1109;
import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_3417;
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
	private static final int C_HEADER = 0xFF232425;
	private static final int C_GLASS = 0xE6232425; // шапка и строка состояния: чуть прозрачные, сквозь них видна панорама
	private static final int C_DIM = 0x141C1D1D; // затемнение панорамы
	private static final int C_FIELD = 0xFF151616;
	private static final int C_PANEL = 0xFF2A2B2C;
	private static final int C_PANEL_HOVER = 0xFF323334;
	private static final int C_BLACK = 0xFF0E0E0F;
	private static final int C_BORDER = C_BLACK;
	private static final int C_CHIP = 0xFF353637;
	private static final int C_WHITE = 0xFFFFFFFF;
	private static final int C_TEXT = 0xFFD0D0D6;
	private static final int C_GRAY = 0xFF8E8E98;
	// акцент — серебро (синяя версия 0061FE сохранена в теге archive/blue-accent)
	private static final int C_ACCENT = 0xFFC9CCD2;
	private static final int C_ACCENT_HOVER = 0xFFE8EAEE;
	private static final int C_ACCENT_TEXT = 0xFF17181A;
	private static final int C_OK = 0xFFE3E6EB;
	private static final int C_FILL = 0xFF6E7279; // заливка прогресса внутри кнопки: на ней читается и белый текст
	private static final int C_WARN = 0xFFFFC857;
	private static final int C_SECONDARY = 0xFF39393B;
	private static final int C_SECONDARY_HOVER = 0xFF47484A;
	private static final int C_DISABLED = 0xFF2E2F30;
	private static final int C_RED = 0xFFFF8A8A;
	private static final int GEAR_W = 18;
	private static final int BTN_H = 18; // высота кнопок в карточке и строке состояния
	private static final int BTN_Y = 32; // кнопки в карточке: отступ сверху
	private static final int CHIP_H = 18; // как у кнопок
	private static final int PANEL_CHIP_H = 14; // в узкой панели теги ниже, чтобы не отнимать место у описания
	private static final int C_TAG_TEXT = 0xFF9DA0A6;
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
	// Отступы иконки от внутренней кромки карточки одинаковы по обеим осям: рамка (контур + обводка) занимает 2 единицы,
	// снизу ещё полоса в 2 — область внутри 52×51, иконка 40 стоит с отступом 6 слева и сверху и 5 снизу.
	private static final int ICON_X = 8;
	private static final int ICON_Y = 8;
	private static final int RIGHT_COL = 112;
	private static final int SCROLLBAR_W = 5;
	private static final int PANEL_W = 190;
	private static final int LINE_H = 11;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "argentum-catalog");
		t.setDaemon(true);
		return t;
	});

	/** Короткие запросы по клику пользователя (список версий, проверка обновлений) — не в очереди за долгой установкой. */
	private static final ExecutorService QUICK = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "argentum-catalog-quick");
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
	private int devStep2;
	private boolean devConfigOpened;
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

	// отключение, обновления, фильтры, выбор версии, диагностика
	private volatile Set<String> disabledIds = Collections.emptySet();
	private volatile Map<String, String> installedVersions = Collections.emptyMap();
	private volatile Map<String, LocalMods.Mod> localMods = Collections.emptyMap();
	private volatile Map<String, ModrinthVersion> updates = Collections.emptyMap();
	private volatile boolean checkingUpdates;
	private String sortKey; // null — по умолчанию
	private final Set<String> categoryFilter = new LinkedHashSet<>();
	private int installedFilter; // 0 — все, 1 — с обновлениями, 2 — отключённые
	private final List<Hit> hits = new ArrayList<>();
	private Popup popup;
	private volatile List<ModrinthVersion> versionList;
	private volatile String versionListFor = "";
	private volatile CrashDiagnoser.Diagnosis diag;
	private boolean modalOpen;
	private float modalAnim;
	private List<ModrinthProject> filteredRows = Collections.emptyList();
	private List<ModrinthProject> filteredSrc;
	private int filteredMode = -1;
	private Map<String, ModrinthVersion> filteredUpd;
	private Set<String> filteredDis;

	/** Область, по которой можно кликнуть. Список пересоздаётся каждый кадр в порядке отрисовки: последняя — самая верхняя. */
	private static final class Hit {
		final String id;
		final ModrinthProject p;
		final int x;
		final int y;
		final int w;
		final int h;
		final boolean listZone;

		Hit(String id, ModrinthProject p, int x, int y, int w, int h, boolean listZone) {
			this.id = id;
			this.p = p;
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.listZone = listZone;
		}
	}

	private static final class PopupItem {
		final String id;
		final String label;
		final boolean checked;
		final int color;

		PopupItem(String id, String label, boolean checked, int color) {
			this.id = id;
			this.label = label;
			this.checked = checked;
			this.color = color;
		}
	}

	/** Всплывающий список под кнопкой: сортировка, фильтры, версии мода. */
	private static final class Popup {
		final String kind;
		final ModrinthProject p;
		final int ax; // правый край кнопки, под которой открыт список
		final int ay; // низ кнопки
		float scroll;
		int x;
		int y;
		int w;
		int h;

		Popup(String kind, ModrinthProject p, int ax, int ay) {
			this.kind = kind;
			this.p = p;
			this.ax = ax;
			this.ay = ay;
		}
	}

	/** Что известно про настройки установленного мода. modId == null — мод ещё не загружен (игру не перезапускали). */
	private static final class ConfigInfo {
		final String modId;
		final boolean screen;
		final List<Path> files;

		ConfigInfo(String modId, boolean screen, List<Path> files) {
			this.modId = modId;
			this.screen = screen;
			this.files = files;
		}

		boolean usable() {
			return this.modId != null && (this.screen || !this.files.isEmpty());
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

		// Dev: с флагом .argentum-config-dev на 20-й секунде открываем редактор настроек первого мода, у которого они есть
		if (!this.devConfigOpened && t > 20000 && java.nio.file.Files.exists(FabricLoader.getInstance().getGameDir().resolve(".argentum-config-dev"))) {
			this.devConfigOpened = true;

			for (ModrinthProject candidate : this.catalogRows) {
				ConfigInfo info = this.configInfo.get(candidate.id);
				Log.info(LogCategory.MODRINTH, "dev: %s -> %s files=%s", candidate.title, info == null ? "нет" : info.modId + "/" + info.screen, info == null ? "-" : info.files);

				if (info != null && info.usable()) {
					openConfig(candidate);
					return;
				}
			}
		}

		if (this.modalOpen && t > 4000) {
			closeModal(); // dev: окно диагностики уже снято, дальше проверяем остальное
		}

		// Dev: всплывающие списки для снимков — фильтры каталога и список версий мода из панели
		if (this.devStep2 == 0 && t > 5000) {
			this.popup = new Popup("filter", null, this.field_22789 - PAD, TABS_Y - 1 + BTN_H + 2);
			this.devStep2 = 1;
		} else if (this.devStep2 == 1 && t > 7500) {
			this.popup = null;
			this.devStep2 = 2;
		} else if (this.devStep2 == 2 && t > 12000 && this.tab == Tab.INSTALLED && this.detail != null) {
			openVersions(this.detail, new Hit("ver", this.detail, panelX(this.field_22789) + 8, LIST_TOP + 90, PANEL_W - 16, 20, false));
			this.devStep2 = 3;
		} else if (this.devStep2 == 3 && t > 14400) {
			this.popup = null;
			this.devStep2 = 4;
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

			// если игра в прошлый раз упала — выясняем вероятного виновника (в фоне) и предлагаем его отключить
			WORKER.submit(() -> {
				CrashDiagnoser.Diagnosis d = CrashDiagnoser.diagnose(FabricLoader.getInstance().getGameDir(), modsDir());

				if (d != null) {
					this.diag = d;
					this.modalOpen = true;
				}
			});

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

	/** Короткая причина ошибки для строки состояния: без цепочек исключений, до первой точки. */
	private static String shortError(Throwable e) {
		String m = e.getMessage();

		if (m == null || m.isEmpty()) {
			return e.getClass().getSimpleName();
		}

		int dot = m.indexOf(". ");
		String first = dot > 0 ? m.substring(0, dot) : m;

		return first.length() > 60 ? first.substring(0, 57) + "..." : first;
	}

	private void setStatus(String s, int color) {
		this.status = s;
		this.statusColor = color;
	}

	private List<ModrinthProject> rows() {
		if (this.tab == Tab.CATALOG) {
			return this.catalogRows;
		}

		List<ModrinthProject> all = this.installedRows;

		if (this.installedFilter == 0) {
			return all;
		}

		if (this.filteredSrc != all || this.filteredMode != this.installedFilter || this.filteredUpd != this.updates || this.filteredDis != this.disabledIds) {
			List<ModrinthProject> out = new ArrayList<>();

			for (ModrinthProject p : all) {
				if (this.installedFilter == 1 ? this.updates.containsKey(p.id) : this.disabledIds.contains(p.id)) {
					out.add(p);
				}
			}

			this.filteredRows = out;
			this.filteredSrc = all;
			this.filteredMode = this.installedFilter;
			this.filteredUpd = this.updates;
			this.filteredDis = this.disabledIds;
		}

		return this.filteredRows;
	}

	private void refreshInstalledIndex() {
		InstalledModsIndex index = InstalledModsIndex.load(modsDir());
		Map<String, String> files = new LinkedHashMap<>();
		Map<String, String> versions = new HashMap<>();

		for (Map.Entry<String, InstalledModsIndex.Entry> e : index.entries().entrySet()) {
			files.put(e.getKey(), e.getValue().fileName);
			versions.put(e.getKey(), e.getValue().versionId);
		}

		this.installedVersions = versions;

		this.installedFiles = files;

		Map<String, ConfigInfo> info = new HashMap<>();

		for (Map.Entry<String, String> e : files.entrySet()) {
			String modId = ModConfigs.modIdForFile(e.getValue());
			ModrinthProject known = this.cache.project(e.getKey());
			info.put(e.getKey(), new ConfigInfo(modId, modId != null && ModConfigs.hasScreen(modId),
					modId == null ? Collections.emptyList() : ModConfigs.configFiles(modId, known == null ? null : known.slug)));
		}

		this.configInfo = info;

		Set<String> disabled = new HashSet<>();

		for (Map.Entry<String, String> e : files.entrySet()) {
			if (LocalMods.state(modsDir(), e.getValue()) == LocalMods.State.DISABLED) {
				disabled.add(e.getKey());
			}
		}

		this.disabledIds = disabled;
	}

	/** Содержимое папки mods с метаданными (хеши считаются один раз на файл) — для локальных карточек. Вызывать из фона. */
	private void refreshLocalMods() {
		Map<String, LocalMods.Mod> m = new HashMap<>();

		for (LocalMods.Mod mod : LocalMods.scan(modsDir())) {
			m.put(mod.fileName, mod);
		}

		this.localMods = m;
	}

	private boolean isDisabled(ModrinthProject p) {
		return this.disabledIds.contains(p.id);
	}

	/**
	 * Запуск поиска. Для пустого запроса (популярное) сразу показываем сохранённый список и обновляем его, если он
	 * старше десяти минут. Результаты устаревшего запроса отбрасываются по токену — быстрый ввод ничего не ломает.
	 */
	private void search(String q) {
		int token = ++this.requestToken;
		this.activeQuery = q;
		this.loadingMore = false;
		boolean defaults = this.sortKey == null && this.categoryFilter.isEmpty();
		boolean popular = q.isEmpty() && defaults;
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
		setStatus(popular ? "Популярные моды для " + this.gameVersion : q.isEmpty() ? "Применяю фильтры" : "Ищу «" + q + "»", C_GRAY);

		WORKER.submit(() -> {
			try {
				ModrinthClient.SearchPage page = ModrinthClient.searchPage(q, LOADER, this.gameVersion, 0, PAGE_SIZE, this.sortKey, new ArrayList<>(this.categoryFilter));

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
					setStatus(hadSaved ? "Нет сети — список из кэша" : "Поиск не удался: " + shortError(e), hadSaved ? C_GRAY : C_RED);
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
				ModrinthClient.SearchPage page = ModrinthClient.searchPage(q, LOADER, this.gameVersion, offset, PAGE_SIZE, this.sortKey, new ArrayList<>(this.categoryFilter));
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

	/** Установленные: мгновенно из кэша, затем в фоне опознаём ручные моды, подтягиваем данные из сети и ищем обновления. */
	private void loadInstalled() {
		int token = ++this.requestToken;
		refreshInstalledIndex();
		this.loadingMore = false;
		this.installedRows = buildInstalledRows(new ArrayList<>(this.installedFiles.keySet()));
		this.listEpoch++;
		this.scrollTarget = 0;
		this.scroll = 0;
		this.loading = true;
		setStatus(this.installedRows.isEmpty() ? "Здесь пока пусто" : "Установлено: " + this.installedRows.size(), C_GRAY);

		WORKER.submit(() -> {
			try {
				refreshLocalMods();

				// моды, которые лежат в mods, но поставлены не через каталог, опознаются по SHA-1 и получают свои карточки
				try {
					LocalMods.identify(modsDir(), InstalledModsIndex.load(modsDir()));
				} catch (Exception e) {
					Log.warn(LogCategory.MODRINTH, "Не удалось опознать моды по хешам", e);
				}

				refreshInstalledIndex();
				List<String> ids = new ArrayList<>(this.installedFiles.keySet());

				if (token == this.requestToken) {
					this.installedRows = buildInstalledRows(ids);
					setStatus(ids.isEmpty() ? "Здесь пока пусто" : "Установлено: " + ids.size(), C_GRAY);
				}

				List<String> remote = new ArrayList<>();

				for (String id : ids) {
					if (!id.startsWith(LocalMods.LOCAL_PREFIX)) {
						remote.add(id);
					}
				}

				try {
					this.cache.putProjects(ModrinthClient.getProjects(remote));
					this.cache.save();

					if (token == this.requestToken) {
						this.installedRows = buildInstalledRows(new ArrayList<>(this.installedFiles.keySet()));
					}
				} catch (Exception e) {
					Log.warn(LogCategory.MODRINTH, "Не удалось обновить список установленных", e);

					if (token == this.requestToken) {
						setStatus("Нет сети — данные из кэша", C_GRAY);
					}
				}

				checkUpdatesNow();
			} finally {
				if (token == this.requestToken) {
					this.loading = false;
				}
			}
		});
	}

	private List<ModrinthProject> buildInstalledRows(List<String> ids) {
		List<ModrinthProject> rows = new ArrayList<>();

		for (String id : ids) {
			if (!this.installedFiles.containsKey(id)) {
				continue; // мод успели удалить — не возвращаем его в список
			}

			ModrinthProject p = this.cache.project(id);
			rows.add(p != null ? p : fallbackProject(id));
		}

		return rows;
	}

	/** Ищет обновления для установленных модов (сеть; вызывать из фона). */
	private void checkUpdatesNow() {
		if (this.checkingUpdates) {
			return;
		}

		this.checkingUpdates = true;

		try {
			Map<String, ModrinthVersion> up = ModUpdates.check(modsDir(), InstalledModsIndex.load(modsDir()), LOADER, this.gameVersion);
			this.updates = up;

			if (!up.isEmpty()) {
				setStatus("Доступно обновлений: " + up.size(), C_OK);
			}
		} catch (Exception e) {
			Log.warn(LogCategory.MODRINTH, "Не удалось проверить обновления", e);
		} finally {
			this.checkingUpdates = false;
		}
	}

	/** Карточка для мода, о котором в кэше ничего нет (ручной или без сети): имя и описание из самого jar. */
	private ModrinthProject fallbackProject(String id) {
		String file = this.installedFiles.getOrDefault(id, id);
		LocalMods.Mod local = this.localMods.get(file);
		String title = local != null && local.name != null ? local.name : file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
		String description = local != null && local.description != null ? local.description : "";

		return new ModrinthProject(id, null, title, description, null, 0, 0, null, null, Collections.emptyList(), null, null, 0);
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

		setStatus("Подбираю «" + p.title + "»", C_GRAY);
		this.installFraction = -1F;
		this.progressById.put(p.id, -1F);

		WORKER.submit(() -> {
			boolean handedOver = false;

			try {
				InstalledModsIndex index = InstalledModsIndex.load(modsDir());
				List<ModrinthVersion> plan = DependencyResolver.resolve(p.id, LOADER, this.gameVersion, index);

				if (plan.isEmpty()) {
					setStatus("«" + p.title + "»: нет версии под " + this.gameVersion, C_RED);
					return;
				}

				ModInstaller.InstallResult result = ModInstaller.install(modsDir(), plan, (fileNo, count, name, done, total) -> {
					float fraction = total > 0 ? (fileNo + (float) done / total) / count : (float) fileNo / count;
					this.installFraction = fraction;
					this.progressById.put(p.id, fraction);
					setStatus("Скачиваю «" + p.title + "» " + (fileNo + 1) + "/" + count, C_GRAY);
				});
				this.installFraction = -1F; // дальше — подключение к игре, прогресс неизвестен
				this.progressById.put(p.id, -1F);
				this.cache.putProjects(Collections.singletonList(p)); // карточка нужна вкладке «Установленные» без сети
				this.cache.save();
				refreshInstalledIndex();

				setStatus("Подключаю «" + p.title + "»", C_GRAY);

				// hot-loader трогает классы игры и запускает инициализаторы модов — это делаем в потоке рендера
				handedOver = true;
				this.field_22787.execute(() -> hotLoad(result.installedFiles, p.title, p.id));
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Установка «" + p.title + "» не удалась", e);
				setStatus("Не удалось установить «" + p.title + "»: " + shortError(e), C_RED);
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
			setStatus(failed ? "«" + names + "» не запустился" : restart ? "«" + names + "» — после перезапуска" : "«" + names + "» работает", failed ? C_RED : restart ? C_WARN : C_OK);

			if (failed || restart) {
				Handoff.requestPrepare(); // то, что не подключилось на лету, готовим в фоновой копии игры
			}

			if (report.needsResourceReload()) {
				this.field_22787.method_1521(); // reloadResources: игра увидит текстуры и переводы новых модов
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.MODRINTH, "Hot-loader не справился", t);
			setStatus("«" + names + "» — после перезапуска", C_WARN);
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

		for (String name : new String[] {fileName, fileName + LocalMods.DISABLED_SUFFIX}) {
			try {
				Files.deleteIfExists(modsDir().resolve(name));
			} catch (IOException e) {
				// Windows не даёт удалить jar, который игра сейчас держит открытым — удалим его при следующем запуске
				try {
					PendingRemovals.schedule(modsDir(), name);
					deferred = true;
				} catch (IOException e2) {
					setStatus("Не удалось удалить файл мода", C_RED);
					this.busyIds.remove(p.id);
					return;
				}
			}
		}

		try {
			InstalledModsIndex index = InstalledModsIndex.load(modsDir());
			index.remove(p.id);
			index.save();
		} catch (IOException e) {
			setStatus("Файл удалён, реестр не обновился", C_RED);
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

		setStatus(deferred ? "«" + p.title + "» удалится при перезапуске" : "«" + p.title + "» удалён", deferred ? C_WARN : C_OK);
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
			setStatus("«" + p.title + "» ещё не загружен — нужен перезапуск", C_GRAY);
			return;
		}

		if (c.screen) {
			class_437 screen = ModConfigs.screen(c.modId, this);

			if (screen != null) {
				this.field_22787.method_1507(screen);
				return;
			}
		}

		// у мода нет своего экрана — открываем его файлы настроек во встроенном редакторе
		boolean editable = false;

		for (Path f : c.files) {
			editable |= com.ganichgg.argentum.client.config.ConfigDoc.supports(f);
		}

		if (editable) {
			this.field_22787.method_1507(new ConfigScreen(this, p.title, c.files));
			return;
		}

		if (!c.files.isEmpty()) { // формат редактору незнаком — отдаём системной программе
			Path file = c.files.get(0);
			setStatus("Открываю " + file.getFileName(), C_OK);

			WORKER.submit(() -> {
				try {
					ModConfigs.openExternally(file);
				} catch (IOException e) {
					Log.warn(LogCategory.MODRINTH, "Не удалось открыть " + file, e);
					setStatus("Не удалось открыть " + file.getFileName(), C_RED);
				}
			});

			return;
		}

		setStatus("У «" + p.title + "» нет настроек", C_GRAY);
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
		if (cardW < 300) {
			return 84;
		}

		return this.tab == Tab.INSTALLED && cardW >= 340 ? 150 : RIGHT_COL;
	}

	/**
	 * Прямоугольники кнопок карточки (x, y, w, h): 0 — отключить/включить, 1 — настройки, 2 — обновить, 3 — основное
	 * действие (установить / удалить). Отсутствующая кнопка — null.
	 */
	private int[][] cardButtons(ModrinthProject p, int x, int y, int w) {
		int col = rightCol(w);
		int colX = x + w - col - 5;
		int by = y + BTN_Y;
		int[][] r = new int[4][];
		int cursor = colX;

		if (this.tab == Tab.INSTALLED) {
			r[0] = new int[] {cursor, by, GEAR_W, BTN_H};
			cursor += GEAR_W + 4;
			r[1] = new int[] {cursor, by, GEAR_W, BTN_H};
			cursor += GEAR_W + 4;

			if (this.updates.containsKey(p.id)) {
				r[2] = new int[] {cursor, by, GEAR_W, BTN_H};
				cursor += GEAR_W + 4;
			}
		}

		r[3] = new int[] {cursor, by, colX + col - cursor, BTN_H};

		return r;
	}

	private void addHit(String id, ModrinthProject p, int x, int y, int w, int h, boolean listZone) {
		this.hits.add(new Hit(id, p, x, y, w, h, listZone));
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

		// окно диагностики перехватывает всё
		if (this.diag != null && this.modalOpen) {
			return modalClick(mx, my);
		}

		// открытый всплывающий список: клик по пункту или закрытие
		if (this.popup != null) {
			return popupClick(mx, my);
		}

		// кнопки карточек, панели и шапки (последняя нарисованная — самая верхняя)
		for (int i = this.hits.size() - 1; i >= 0; i--) {
			Hit hit = this.hits.get(i);

			if (inside(mx, my, hit.x, hit.y, hit.w, hit.h) && (!hit.listZone || (my >= LIST_TOP && my < listBottom()))) {
				press(hit.id + (hit.p != null ? ":" + hit.p.id : ""));
				handleHit(hit);

				return true;
			}
		}

		// остальное внутри панели просто не пропускаем к списку
		if (overPanel(mx, my)) {
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
				if (t != this.tab) {
					playClick();
				}

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

				openDetail(p);

				return true;
			}
		}

		return false;
	}

	@Override
	public boolean method_25401(double mx, double my, double horizontal, double vertical) {
		if (this.popup != null) {
			this.popup.scroll = Math.max(0, this.popup.scroll - (float) vertical * 16);

			return true;
		}

		if (overPanel(mx, my)) {
			this.detailScrollTarget = Math.max(0, this.detailScrollTarget - (float) vertical * 30);

			return true;
		}

		this.scrollTarget = Math.max(0, Math.min(maxScroll(), this.scrollTarget - (float) vertical * 30));

		return true;
	}

	@Override
	public boolean method_25404(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 256 && this.diag != null && this.modalOpen) {
			closeModal();
			return true;
		}

		if (keyCode == 256 && this.popup != null) {
			this.popup = null;
			return true;
		}

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

	/** Плашка-тег: ровная заливка чуть светлее фона и тёмная нижняя полоса — без обводки, чтобы не спорить с кнопками. */
	private static void chip(class_332 ctx, int x, int y, int w, int h) {
		ctx.method_25294(x, y, x + w, y + h, C_CHIP);
		ctx.method_25294(x, y + h - 2, x + w, y + h, darker(C_CHIP, 0.25F));
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
		playClick();
	}

	/** Ванильный звук нажатия кнопки (SoundEvents.UI_BUTTON_CLICK = class_3417.field_15015). */
	private void playClick() {
		this.field_22787.method_1483().method_4873(class_1109.method_47978(class_3417.field_15015, 1.0F));
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

		if (fill == -2F) { // прогресс неизвестен: по полосе бежит отрезок
			int inner = w - 4;
			int seg = Math.max(10, inner * 3 / 10);
			float phase = (System.currentTimeMillis() % 1300) / 1300F;
			int from = Math.max(0, (int) (-seg + (inner + seg) * phase));
			int to = Math.min(inner, (int) ((-seg + (inner + seg) * phase) + seg));

			if (to > from) {
				int bottom = y + h - (pressed ? 3 : 5);
				ctx.method_25294(x + 2 + from, y + 2, x + 2 + to, bottom, C_FILL);
				ctx.method_25294(x + 2 + from, y + 2, x + 2 + to, y + 3, lighter(C_FILL, 0.25F));
			}
		} else if (fill >= 0F) {
			int fw = Math.round((w - 4) * Math.min(1F, fill));

			if (fw > 0) {
				int bottom = y + h - (pressed ? 3 : 5);
				ctx.method_25294(x + 2, y + 2, x + 2 + fw, bottom, C_FILL);
				ctx.method_25294(x + 2, y + 2, x + 2 + fw, y + 3, lighter(C_FILL, 0.25F));
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

	/** Всё, что нарисовано между fadeBegin и fadeEnd, выводится с прозрачностью alpha (модуляция цвета при сбросе буфера). */
	private static void fadeBegin(class_332 ctx, float alpha) {
		ctx.method_51452();
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1F, 1F, 1F, Math.max(0F, Math.min(1F, alpha)));
	}

	private static void fadeEnd(class_332 ctx) {
		ctx.method_51452();
		RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
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
		this.hits.clear();

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

		// Фон — панорама главного меню с ванильным размытием (renderBackground родителя); интерфейс рисуем уже поверх,
		// иначе размытие накрыло бы и его. Сверху лёгкое затемнение, чтобы карточки читались.
		super.method_25420(ctx, mx, my, delta);
		ctx.method_25294(0, 0, w, h, C_DIM);

		drawHeader(ctx, mx, my, w);
		drawList(ctx, mx, my, dt, w);
		drawPanel(ctx, mx, my, w);
		drawStatusBar(ctx, mx, my, w, h);
		drawPopup(ctx, mx, my);
		drawModal(ctx, mx, my, w, h);
	}

	private void drawHeader(class_332 ctx, int mx, int my, int w) {
		ctx.method_25294(0, 0, w, LIST_TOP - 2, C_GLASS);
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

		drawHeaderControls(ctx, mx, my, w);
	}

	private static final String[][] SORTS = {{"", "По умолчанию"}, {"relevance", "Релевантность"}, {"downloads", "Загрузки"}, {"follows", "Лайки"}, {"newest", "Новые"}, {"updated", "Недавно обновлённые"}};
	private static final String[] INSTALLED_FILTERS = {"Все", "С обновлениями", "Отключённые"};

	private String sortLabel() {
		for (String[] s : SORTS) {
			if (s[0].equals(this.sortKey == null ? "" : this.sortKey)) {
				return s[1];
			}
		}

		return SORTS[0][1];
	}

	/** Кнопки справа от вкладок (то, что не помещается, пропускается): для каталога — сортировка и фильтры, для установленных — фильтр, обновления, импорт. */
	private void drawHeaderControls(class_332 ctx, int mx, int my, int w) {
		List<String[]> buttons = new ArrayList<>(); // id, подпись, со стрелкой?

		if (this.tab == Tab.CATALOG) {
			buttons.add(new String[] {"filter", "Фильтры" + (this.categoryFilter.isEmpty() ? "" : " (" + this.categoryFilter.size() + ")"), "1"});
			buttons.add(new String[] {"sort", "Сортировка: " + sortLabel(), "1"});
		} else {
			buttons.add(new String[] {"import", "Импорт сборки", "0"});
			buttons.add(new String[] {"updall", this.updates.isEmpty() ? (this.checkingUpdates ? "Проверяю..." : "Проверить обновления") : "Обновить всё (" + this.updates.size() + ")", "0"});
			buttons.add(new String[] {"instfilter", INSTALLED_FILTERS[this.installedFilter], "1"});
		}

		int right = w - PAD;
		int limit = tabX(Tab.INSTALLED) + tabWidth(Tab.INSTALLED) + 12;

		for (String[] b : buttons) {
			boolean arrow = b[2].equals("1");
			int bw = textWidth(b[1]) + 16 + (arrow ? 10 : 0);

			if (right - bw < limit) {
				continue;
			}

			int bx = right - bw;
			int by = TABS_Y - 1;
			boolean hv = inside(mx, my, bx, by, bw, BTN_H) && this.popup == null;
			boolean accent = b[0].equals("updall") && !this.updates.isEmpty();
			int textColor = accent ? C_ACCENT_TEXT : C_TEXT;
			button(ctx, "h:" + b[0], bx, by, bw, BTN_H, "", null, false, accent ? C_ACCENT : C_SECONDARY, accent ? C_ACCENT_HOVER : C_SECONDARY_HOVER, hv, textColor);
			text(ctx, b[1], bx + 8, by + 5, textColor);

			if (arrow) {
				PixelIcons.draw(ctx, PixelIcons.ARROW_DOWN, bx + bw - 15, by + 7, textColor);
			}

			addHit(b[0], null, bx, by, bw, BTN_H, false);
			right = bx - 4;
		}
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
					fadeBegin(ctx, 1F - e);
					drawCard(ctx, p, PAD, y, cardW, -1000, -1000, dt);
					fadeEnd(ctx);
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

			float shownAlpha = a * (isDisabled(p) ? 0.5F : 1F);

			if (shownAlpha < 1F) {
				fadeBegin(ctx, shownAlpha);
			}

			drawCard(ctx, p, PAD, y + dy, cardW, mx, my, dt);

			if (shownAlpha < 1F) {
				fadeEnd(ctx);
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
			int textX = x + ICON_X + ICON + 8;
			int rightX = x + cardW - col - 4;
			int textEnd = rightX - 6;

			frame(ctx, x, y, cardW, CARD_H, C_PANEL, lighter(C_PANEL, 0.07F), darker(C_PANEL, 0.3F), false);
			ctx.method_25294(x + ICON_X, y + ICON_Y, x + ICON_X + ICON, y + ICON_Y + ICON, bar);
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
		float[] t = this.tilt.computeIfAbsent(p.id, k -> new float[3]);
		float targetY = 0F;
		float targetX = 0F;

		if (hovered) {
			targetY = clamp((mx - (x + ICON_X + ICON / 2F)) / 90F) * TILT_MAX;
			targetX = clamp((my - (y + ICON_Y + ICON / 2F)) / 40F) * TILT_MAX;
		}

		t[0] = approach(t[0], targetY, dt, 14);
		t[1] = approach(t[1], targetX, dt, 14);
		t[2] = approach(t[2], hovered ? 1F : 0F, dt, 12);
		drawIcon(ctx, p, x + ICON_X, y + ICON_Y, ICON, t[0], t[1], t[2]);

		int col = rightCol(w);
		int textX = x + ICON_X + ICON + 8;
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
		int chipX = textX + drawBadge(ctx, p, textX, y + BTN_Y, textW);

		for (String tag : Fmt.tags(p)) {
			int chipW = textWidth(tag) + 12;

			if (chipX + chipW > textX + textW) {
				break;
			}

			chip(ctx, chipX, y + BTN_Y, chipW, CHIP_H);
			text(ctx, tag, chipX + 6, y + BTN_Y + 4, C_TAG_TEXT);
			chipX += chipW + 4;
		}

		// правая колонка: скачивания, лайки, обновление, кнопки
		if (!noStats(p)) {
			drawStats(ctx, p, rightX, y + 8, col);
		}

		int[][] r = cardButtons(p, x, y, w);
		boolean inList = my >= LIST_TOP && my < listBottom();

		if (r[0] != null) {
			boolean off = isDisabled(p);
			boolean hv = inList && inside(mx, my, r[0][0], r[0][1], r[0][2], r[0][3]);
			button(ctx, "tg:" + p.id, r[0][0], r[0][1], r[0][2], r[0][3], null, PixelIcons.POWER, false, off ? C_DISABLED : C_SECONDARY,
					off ? C_SECONDARY : C_SECONDARY_HOVER, hv, off ? C_WARN : C_WHITE);
			addHit("tg", p, r[0][0], r[0][1], r[0][2], r[0][3], true);
		}

		if (r[1] != null) {
			drawGearButton(ctx, "gear:" + p.id, r[1][0], r[1][1], r[1][2], r[1][3], p, null, inList ? mx : -1000, my);
			addHit("gear", p, r[1][0], r[1][1], r[1][2], r[1][3], true);
		}

		if (r[2] != null) {
			boolean hv = inList && inside(mx, my, r[2][0], r[2][1], r[2][2], r[2][3]);
			button(ctx, "up:" + p.id, r[2][0], r[2][1], r[2][2], r[2][3], null, PixelIcons.UP, false, C_ACCENT, C_ACCENT_HOVER, hv, C_ACCENT_TEXT);
			addHit("up", p, r[2][0], r[2][1], r[2][2], r[2][3], true);
		}

		drawActionButton(ctx, "act:" + p.id, p, r[3][0], r[3][1], r[3][2], r[3][3], inList ? mx : -1000, my);
		addHit("act", p, r[3][0], r[3][1], r[3][2], r[3][3], true);
	}

	private static float clamp(float v) {
		return Math.max(-1F, Math.min(1F, v));
	}

	/** Состояние установленного мода: «Активен» / «После перезапуска» / «Отключён», «Обновление», «Локальный». Возвращает занятую ширину. */
	private int drawBadge(class_332 ctx, ModrinthProject p, int x, int y, int maxWidth) {
		if (this.tab != Tab.INSTALLED) {
			return 0;
		}

		int used = 0;

		if (isDisabled(p)) {
			used += badge(ctx, "Отключён", C_GRAY, x + used, y, maxWidth - used);
		} else {
			ConfigInfo c = this.configInfo.get(p.id);
			boolean active = c != null && c.modId != null;
			used += badge(ctx, active ? "Активен" : "После перезапуска", active ? C_OK : C_WARN, x + used, y, maxWidth - used);
		}

		if (this.updates.containsKey(p.id)) {
			used += badge(ctx, "Обновление", C_ACCENT, x + used, y, maxWidth - used);
		}

		if (p.id.startsWith(LocalMods.LOCAL_PREFIX)) {
			used += badge(ctx, "Локальный", C_GRAY, x + used, y, maxWidth - used);
		}

		return used;
	}

	private int badge(class_332 ctx, String label, int color, int x, int y, int maxWidth) {
		int chipW = textWidth(label) + 12;

		if (chipW > maxWidth) {
			return 0;
		}

		chip(ctx, x, y, chipW, CHIP_H);
		text(ctx, label, x + 6, y + 4, color);

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
			buttonFill(ctx, key, x, y, w, h, fl >= 0F ? Math.round(shown * 100) + "%" : "Установка", null, false, C_DISABLED, C_DISABLED, false, C_WHITE, fl >= 0F ? shown : -2F);
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
		drawIcon(ctx, p, x, y, size, 0F, 0F, 0F);
	}

	private void drawIcon(class_332 ctx, ModrinthProject p, int x, int y, int size, float ay, float bx, float pop) {
		IconCache.Icon icon = this.icons.get(p);

		if (icon.texture == null) {
			// плейсхолдер: цвет проекта и первая буква — пока иконка грузится (или если её не удалось получить)
			int color = p.color != 0 ? (0xFF000000 | p.color) : C_CHIP;
			int fill = lerpColor(color, C_PANEL, 0.55F);
			ctx.method_25294(x, y, x + size, y + size, C_BLACK); // чёрный контур
			ctx.method_25294(x + 1, y + 1, x + size - 1, y + size - 1, lighter(fill, 0.22F)); // светлая кромка
			ctx.method_25294(x + 2, y + 2, x + size - 2, y + size - 2, fill);

			if (p.title != null && !p.title.isEmpty()) {
				String letter = p.title.substring(0, 1).toUpperCase();
				text(ctx, letter, x + (size - textWidth(letter)) / 2, y + (size - 8) / 2 + 1, C_WHITE);
			}

			return;
		}

		// иконка плавно проявляется
		float alpha = Math.min(1F, (System.nanoTime() - icon.shownAtNanos) / 200_000_000F);

		if (Math.abs(ay) > 0.004F || Math.abs(bx) > 0.004F || pop > 0.01F) {
			Icon3D.draw(ctx, icon.texture, x, y, size, ay, bx, alpha, pop);
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
		float[] pt = this.tilt.computeIfAbsent("panel", k -> new float[3]);
		pt[0] = approach(pt[0], overIcon ? clamp((mx - (innerX + 14)) / 40F) * TILT_MAX : 0F, this.dt, 14);
		pt[1] = approach(pt[1], overIcon ? clamp((my - (py + 22)) / 28F) * TILT_MAX : 0F, this.dt, 14);
		pt[2] = approach(pt[2], overIcon ? 1F : 0F, this.dt, 12);
		drawIcon(ctx, p, innerX, py + 8, 28, pt[0], pt[1], pt[2]);
		int titleX = innerX + 28 + 6;
		int titleW = innerW - 28 - 6 - 18;
		text(ctx, fit(p.title, titleW), titleX, py + 9, C_WHITE);

		if (p.author != null) {
			text(ctx, fit("by " + p.author, titleW), titleX, py + 21, C_GRAY);
		}

		int cx = px + PANEL_W - 8 - 16;
		button(ctx, "close", cx, py + 5, 16, 16, null, PixelIcons.CLOSE, false, C_SECONDARY, C_SECONDARY_HOVER, inside(mx, my, cx, py + 5, 16, 16), C_WHITE);
		addHit("close", p, cx, py + 5, 16, 16, false);

		int y = py + 42;

		if (!noStats(p)) {
			drawStats(ctx, p, innerX, y, innerW);
			y += 26;
		}

		// теги с переносом на следующие строки
		int chipX = innerX + drawBadge(ctx, p, innerX, y, innerW);

		for (String tag : Fmt.tags(p)) {
			int chipW = textWidth(tag) + 12;

			if (chipX + chipW > innerX + innerW) {
				chipX = innerX;
				y += PANEL_CHIP_H + 3;
			}

			chip(ctx, chipX, y, chipW, PANEL_CHIP_H);
			text(ctx, tag, chipX + 6, y + 3, C_TAG_TEXT);
			chipX += chipW + 4;
		}

		y += PANEL_CHIP_H + 6;

		// кнопки панели: каждая регистрируется как область клика
		int bh = 20;
		boolean local = p.id.startsWith(LocalMods.LOCAL_PREFIX);

		if (!local) {
			boolean hv = inside(mx, my, innerX, y, innerW, bh) && this.popup == null;
			button(ctx, "ver", innerX, y, innerW, bh, "", null, false, C_SECONDARY, C_SECONDARY_HOVER, hv, C_TEXT);
			text(ctx, "Выбрать версию", innerX + 8, y + 6, C_TEXT);
			PixelIcons.draw(ctx, PixelIcons.ARROW_DOWN, innerX + innerW - 15, y + 8, C_TEXT);
			addHit("ver", p, innerX, y, innerW, bh, false);
			y += bh + 4;
		}

		if (this.tab == Tab.INSTALLED) {
			int half = (innerW - 4) / 2;
			boolean off = isDisabled(p);
			drawGearButton(ctx, "panel-gear", innerX, y, half, bh, p, "Настройки", mx, my);
			addHit("gear", p, innerX, y, half, bh, false);
			button(ctx, "panel-tg", innerX + half + 4, y, innerW - half - 4, bh, off ? "Включить" : "Отключить", PixelIcons.POWER, false,
					C_SECONDARY, C_SECONDARY_HOVER, inside(mx, my, innerX + half + 4, y, innerW - half - 4, bh), off ? C_WARN : C_TEXT);
			addHit("tg", p, innerX + half + 4, y, innerW - half - 4, bh, false);
			y += bh + 4;

			if (this.updates.containsKey(p.id)) {
				button(ctx, "panel-up", innerX, y, half, bh, "Обновить", PixelIcons.UP, false, C_ACCENT, C_ACCENT_HOVER, inside(mx, my, innerX, y, half, bh), C_ACCENT_TEXT);
				addHit("up", p, innerX, y, half, bh, false);
				drawActionButton(ctx, "panel-act", p, innerX + half + 4, y, innerW - half - 4, bh, mx, my);
				addHit("act", p, innerX + half + 4, y, innerW - half - 4, bh, false);
			} else {
				drawActionButton(ctx, "panel-act", p, innerX, y, innerW, bh, mx, my);
				addHit("act", p, innerX, y, innerW, bh, false);
			}
		} else {
			drawActionButton(ctx, "panel-act", p, innerX, y, innerW, bh, mx, my);
			addHit("act", p, innerX, y, innerW, bh, false);
		}

		y += bh + 8;

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
		ctx.method_25294(0, top, w, h, C_GLASS);
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
				fill = -2F;
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

	// ---------------------------------------------------------------- отключение, обновления, версии, импорт, диагностика

	private void handleHit(Hit hit) {
		ModrinthProject p = hit.p;

		switch (hit.id) {
		case "tg":
			toggleEnabled(p);
			break;
		case "gear":
			openConfig(p);
			break;
		case "up": {
			ModrinthVersion v = this.updates.get(p.id);

			if (v != null) {
				applyVersion(p, v, true);
			}

			break;
		}
		case "act":
			act(p);
			break;
		case "close":
			closeDetail();
			break;
		case "ver":
			openVersions(p, hit);
			break;
		case "filter":
		case "sort":
		case "instfilter":
			this.popup = new Popup(hit.id, null, hit.x + hit.w, hit.y + hit.h + 2);
			break;
		case "updall":
			if (this.updates.isEmpty()) {
				if (!this.checkingUpdates) {
					setStatus("Проверяю обновления", C_GRAY);
					QUICK.submit(() -> {
						checkUpdatesNow();
						setStatus(this.updates.isEmpty() ? "Все моды актуальны" : "Доступно обновлений: " + this.updates.size(), this.updates.isEmpty() ? C_OK : C_ACCENT);
					});
				}
			} else {
				updateAll();
			}

			break;
		case "import":
			pickAndImport();
			break;
		default:
			break;
		}
	}

	/** Включает/отключает мод переименованием jar. Включённый мод пробуем сразу подключить к игре, отключённый — снимется после перезапуска. */
	private void toggleEnabled(ModrinthProject p) {
		String file = this.installedFiles.get(p.id);

		if (file == null || !this.busyIds.add(p.id)) {
			return;
		}

		boolean enable = isDisabled(p);

		WORKER.submit(() -> {
			boolean handed = false;

			try {
				if (enable) {
					LocalMods.enable(modsDir(), file);
					refreshInstalledIndex();
					refreshLocalMods();
					handed = true;
					this.field_22787.execute(() -> hotLoad(Collections.singletonList(modsDir().resolve(file)), p.title, p.id));
				} else {
					boolean now = LocalMods.disable(modsDir(), file);
					refreshInstalledIndex();
					refreshLocalMods();

					if (now) {
						setStatus("«" + p.title + "» отключён", C_OK);
					} else {
						Handoff.requestPrepare();
						setStatus("«" + p.title + "» отключится при перезапуске", C_WARN);
					}
				}
			} catch (IOException | RuntimeException e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось переключить мод «" + p.title + "»", e);
				setStatus("Не удалось переключить «" + p.title + "»", C_RED);
			} finally {
				if (!handed) {
					this.busyIds.remove(p.id);
				}
			}
		});
	}

	/** Установка выбранной версии: обновление, откат или первая установка конкретной версии. Замена файла вступает в силу после перезапуска. */
	private void applyVersion(ModrinthProject p, ModrinthVersion v, boolean isUpdate) {
		if (!this.busyIds.add(p.id)) {
			return;
		}

		boolean replacing = this.installedFiles.containsKey(p.id);
		setStatus("Ставлю «" + p.title + "» " + v.versionNumber, C_GRAY);
		this.installFraction = -1F;
		this.progressById.put(p.id, -1F);

		WORKER.submit(() -> {
			boolean handed = false;

			try {
				ModInstaller.InstallResult r = ModUpdates.apply(modsDir(), v, LOADER, this.gameVersion, (fileNo, count, name, done, total) -> {
					float fraction = total > 0 ? (fileNo + (float) done / total) / count : (float) fileNo / count;
					this.installFraction = fraction;
					this.progressById.put(p.id, fraction);
					setStatus("Скачиваю «" + p.title + "» " + (fileNo + 1) + "/" + count, C_GRAY);
				});
				this.installFraction = -1F;
				this.cache.putProjects(Collections.singletonList(p));
				this.cache.save();
				refreshInstalledIndex();
				refreshLocalMods();
				Map<String, ModrinthVersion> up = new HashMap<>(this.updates);
				up.remove(p.id);
				this.updates = up;

				if (replacing) {
					Handoff.requestPrepare();
					setStatus("«" + p.title + "» " + (isUpdate ? "обновлён до " : "заменён на ") + v.versionNumber + " — после перезапуска", C_WARN);
				} else {
					handed = true;
					this.field_22787.execute(() -> hotLoad(r.installedFiles, p.title, p.id));
				}
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось поставить «" + p.title + "» " + v.versionNumber, e);
				setStatus("Не удалось поставить «" + p.title + "»: " + shortError(e), C_RED);
			} finally {
				if (!handed) {
					this.busyIds.remove(p.id);
					this.progressById.remove(p.id);
					this.installFraction = -2F;
				}
			}
		});
	}

	private void updateAll() {
		List<Map.Entry<String, ModrinthVersion>> todo = new ArrayList<>(this.updates.entrySet());

		if (todo.isEmpty() || !this.busyIds.add("*all")) {
			return;
		}

		this.installFraction = -1F;
		setStatus("Обновляю моды: " + todo.size(), C_GRAY);

		WORKER.submit(() -> {
			int done = 0;
			int failed = 0;

			try {
				for (Map.Entry<String, ModrinthVersion> e : todo) {
					final int index = done;

					try {
						ModUpdates.apply(modsDir(), e.getValue(), LOADER, this.gameVersion, (fileNo, count, name, bytes, total) -> {
							float inner = total > 0 ? (fileNo + (float) bytes / total) / count : (float) fileNo / count;
							this.installFraction = (index + inner) / todo.size();
						});
					} catch (Exception ex) {
						failed++;
						Log.warn(LogCategory.MODRINTH, "Не удалось обновить " + e.getKey(), ex);
					}

					done++;
					setStatus("Обновлено " + done + " из " + todo.size(), C_GRAY);
				}

				refreshInstalledIndex();
				refreshLocalMods();
				this.updates = Collections.emptyMap();
				Handoff.requestPrepare();
				setStatus(failed == 0 ? "Обновлено модов: " + done + " — после перезапуска" : "Обновлено " + (done - failed) + ", с ошибкой " + failed, failed == 0 ? C_WARN : C_RED);
			} finally {
				this.busyIds.remove("*all");
				this.installFraction = -2F;
			}
		});
	}

	// ---- выбор версии

	private void openVersions(ModrinthProject p, Hit hit) {
		this.popup = new Popup("ver", p, hit.x + hit.w, hit.y + hit.h + 2);

		if (!this.versionListFor.equals(p.id)) {
			this.versionListFor = p.id;
			this.versionList = null;

			QUICK.submit(() -> {
				try {
					List<ModrinthVersion> list = ModUpdates.versions(p.id, LOADER, this.gameVersion);

					if (this.versionListFor.equals(p.id)) {
						this.versionList = list;
					}
				} catch (Exception e) {
					Log.warn(LogCategory.MODRINTH, "Не удалось получить версии «" + p.title + "»", e);
					setStatus("Не удалось получить версии: " + shortError(e), C_RED);
					this.popup = null;
				}
			});
		}
	}

	private List<PopupItem> popupItems(Popup pp) {
		List<PopupItem> items = new ArrayList<>();

		switch (pp.kind) {
		case "sort":
			for (String[] srt : SORTS) {
				items.add(new PopupItem("sort:" + srt[0], srt[1], srt[0].equals(this.sortKey == null ? "" : this.sortKey), C_TEXT));
			}

			break;
		case "filter":
			items.add(new PopupItem("cat:*", "Сбросить категории", false, C_GRAY));

			for (String c : Fmt.FILTER_CATEGORIES) {
				items.add(new PopupItem("cat:" + c, Fmt.categoryLabel(c), this.categoryFilter.contains(c), C_TEXT));
			}

			break;
		case "instfilter":
			for (int i = 0; i < INSTALLED_FILTERS.length; i++) {
				items.add(new PopupItem("if:" + i, INSTALLED_FILTERS[i], i == this.installedFilter, C_TEXT));
			}

			break;
		default: { // версии мода
			List<ModrinthVersion> list = this.versionList;

			if (list == null || pp.p == null || !this.versionListFor.equals(pp.p.id)) {
				items.add(new PopupItem("none", "Загружаю список версий...", false, C_GRAY));
			} else if (list.isEmpty()) {
				items.add(new PopupItem("none", "Нет версий под " + this.gameVersion, false, C_GRAY));
			} else {
				String current = this.installedVersions.get(pp.p.id);

				for (int i = 0; i < list.size(); i++) {
					ModrinthVersion v = list.get(i);
					String type = "beta".equals(v.versionType) ? "бета" : "alpha".equals(v.versionType) ? "альфа" : "релиз";
					int color = "beta".equals(v.versionType) ? C_WARN : "alpha".equals(v.versionType) ? C_RED : C_TEXT;
					String when = Fmt.ago(v.datePublished);
					items.add(new PopupItem("ver:" + i, v.versionNumber + " · " + type + (when.isEmpty() ? "" : " · " + when), v.id.equals(current), color));
				}
			}
		}
		}

		return items;
	}

	private static final int POPUP_ROW = 15;

	private void drawPopup(class_332 ctx, int mx, int my) {
		Popup pp = this.popup;

		if (pp == null) {
			return;
		}

		List<PopupItem> items = popupItems(pp);
		int widest = 0;

		for (PopupItem it : items) {
			widest = Math.max(widest, textWidth(it.label));
		}

		int w = Math.max(130, Math.min(260, widest + 34));
		int maxH = Math.max(60, listBottom() - pp.ay - 6);
		int h = Math.min(items.size() * POPUP_ROW + 6, maxH);
		pp.w = w;
		pp.h = h;
		pp.x = Math.max(4, Math.min(this.field_22789 - w - 4, pp.ax - w));
		pp.y = pp.ay;
		pp.scroll = Math.max(0, Math.min(Math.max(0, items.size() * POPUP_ROW + 6 - h), pp.scroll));

		frame(ctx, pp.x, pp.y, w, h, C_HEADER, lighter(C_HEADER, 0.1F), darker(C_HEADER, 0.35F), false);
		ctx.method_44379(pp.x + 2, pp.y + 2, pp.x + w - 2, pp.y + h - 3);

		for (int i = 0; i < items.size(); i++) {
			PopupItem it = items.get(i);
			int iy = (int) (pp.y + 3 + i * POPUP_ROW - pp.scroll);

			if (iy + POPUP_ROW < pp.y || iy > pp.y + h) {
				continue;
			}

			boolean hv = inside(mx, my, pp.x + 2, iy, w - 4, POPUP_ROW) && my >= pp.y + 2 && my < pp.y + h - 2;

			if (hv && !it.id.equals("none")) {
				ctx.method_25294(pp.x + 2, iy, pp.x + w - 2, iy + POPUP_ROW, C_SECONDARY_HOVER);
			}

			// флажок
			ctx.method_25294(pp.x + 7, iy + 3, pp.x + 16, iy + 12, C_BLACK);
			ctx.method_25294(pp.x + 8, iy + 4, pp.x + 15, iy + 11, it.checked ? C_ACCENT : C_FIELD);
			text(ctx, fit(it.label, w - 34), pp.x + 22, iy + 4, it.color);
		}

		ctx.method_44380();
	}

	private boolean popupClick(double mx, double my) {
		Popup pp = this.popup;

		if (pp != null && inside(mx, my, pp.x, pp.y, pp.w, pp.h)) {
			List<PopupItem> items = popupItems(pp);
			int index = (int) ((my - pp.y - 3 + pp.scroll) / POPUP_ROW);

			if (index >= 0 && index < items.size()) {
				PopupItem it = items.get(index);

				if (!it.id.equals("none")) {
					playClick();
					handlePopupItem(pp, it);
				}
			}

			return true;
		}

		this.popup = null; // клик мимо закрывает список и не проваливается дальше

		return true;
	}

	private void handlePopupItem(Popup pp, PopupItem it) {
		String id = it.id;

		if (id.startsWith("sort:")) {
			String key = id.substring(5);
			this.sortKey = key.isEmpty() ? null : key;
			this.popup = null;
			search(this.query.trim());
		} else if (id.equals("cat:*")) {
			this.categoryFilter.clear();
			search(this.query.trim());
		} else if (id.startsWith("cat:")) {
			String c = id.substring(4);

			if (!this.categoryFilter.remove(c)) {
				this.categoryFilter.add(c);
			}

			search(this.query.trim()); // список остаётся открытым: можно отметить несколько категорий
		} else if (id.startsWith("if:")) {
			this.installedFilter = Integer.parseInt(id.substring(3));
			this.popup = null;
			this.listEpoch++;
			this.scroll = 0;
			this.scrollTarget = 0;
			this.layoutVersion++;
		} else if (id.startsWith("ver:")) {
			List<ModrinthVersion> list = this.versionList;
			int i = Integer.parseInt(id.substring(4));
			this.popup = null;

			if (list != null && i < list.size()) {
				ModrinthVersion v = list.get(i);

				if (it.checked) {
					setStatus("Эта версия уже установлена", C_GRAY);
				} else {
					applyVersion(pp.p, v, false);
				}
			}
		}
	}

	// ---- импорт сборки .mrpack

	private void pickAndImport() {
		QUICK.submit(() -> {
			String path = null;

			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(1);
				filters.put(stack.UTF8("*.mrpack")).flip();
				path = TinyFileDialogs.tinyfd_openFileDialog("Импорт сборки Modrinth", "", filters, "Сборки Modrinth (*.mrpack)", false);
			} catch (Throwable t) {
				Log.warn(LogCategory.MODRINTH, "Диалог выбора файла недоступен", t);
				setStatus("Диалог выбора файла недоступен — перетащи .mrpack в окно", C_WARN);
			}

			if (path != null) {
				importPackFile(Paths.get(path));
			}
		});
	}

	@Override
	public void method_29638(List<Path> paths) {
		for (Path p : paths) {
			if (p.getFileName().toString().toLowerCase().endsWith(".mrpack")) {
				QUICK.submit(() -> importPackFile(p));

				return;
			}
		}

		setStatus("Нужен файл .mrpack", C_GRAY);
	}

	private void importPackFile(Path file) {
		if (!this.busyIds.add("*import")) {
			return;
		}

		this.installFraction = -1F;
		setStatus("Читаю сборку", C_GRAY);
		boolean handed = false;

		try {
			MrpackImporter.Result result = MrpackImporter.importPack(file, FabricLoader.getInstance().getGameDir(), this.gameVersion, (done, total, what) -> {
				this.installFraction = total <= 0 ? -1F : (float) done / total;
				setStatus("Сборка: " + done + "/" + total, C_GRAY);
			});

			try {
				LocalMods.identify(modsDir(), InstalledModsIndex.load(modsDir()));
			} catch (Exception e) {
				Log.warn(LogCategory.MODRINTH, "Не удалось опознать моды сборки", e);
			}

			refreshInstalledIndex();
			refreshLocalMods();
			setStatus("Сборка «" + result.name + "»: скачано " + result.downloaded + ", " + result.newMods.size() + " модов", C_OK);

			if (!result.newMods.isEmpty()) {
				handed = true;
				List<Path> mods = result.newMods;
				this.field_22787.execute(() -> {
					hotLoad(mods, result.name, "*import");
					this.listEpoch++;
					loadInstalled();
				});
			}
		} catch (Exception e) {
			Log.warn(LogCategory.MODRINTH, "Импорт сборки не удался", e);
			setStatus(shortError(e), C_RED);
		} finally {
			if (!handed) {
				this.busyIds.remove("*import");
				this.installFraction = -2F;
			}
		}
	}

	// ---- окно диагностики падения

	private final Map<String, Boolean> suspectDone = new HashMap<>();
	private final List<Hit> modalHits = new ArrayList<>();

	private void closeModal() {
		CrashDiagnoser.Diagnosis d = this.diag;
		this.modalOpen = false;
		this.diag = null;

		if (d != null) {
			CrashDiagnoser.markHandled(FabricLoader.getInstance().getGameDir(), d.crashFile);
		}
	}

	private boolean modalClick(double mx, double my) {
		for (int i = this.modalHits.size() - 1; i >= 0; i--) {
			Hit hit = this.modalHits.get(i);

			if (inside(mx, my, hit.x, hit.y, hit.w, hit.h)) {
				press("modal-" + hit.id);

				if (hit.id.equals("close")) {
					closeModal();
				} else if (hit.id.equals("report")) {
					CrashDiagnoser.Diagnosis d = this.diag;

					if (d != null) {
						QUICK.submit(() -> {
							try {
								ModConfigs.openExternally(FabricLoader.getInstance().getGameDir().resolve("crash-reports").resolve(d.crashFile));
							} catch (IOException e) {
								setStatus("Не удалось открыть отчёт", C_RED);
							}
						});
					}
				} else if (hit.id.startsWith("off:")) {
					String file = hit.id.substring(4);
					this.suspectDone.put(file, true);

					QUICK.submit(() -> {
						try {
							boolean now = LocalMods.disable(modsDir(), file);
							refreshInstalledIndex();
							setStatus(now ? "Мод отключён" : "Мод отключится при перезапуске", now ? C_OK : C_WARN);

							if (!now) {
								Handoff.requestPrepare();
							}
						} catch (IOException e) {
							setStatus("Не удалось отключить мод", C_RED);
						}
					});
				}

				return true;
			}
		}

		return true; // окно модальное: клики мимо не проходят к списку
	}

	private void drawModal(class_332 ctx, int mx, int my, int w, int h) {
		CrashDiagnoser.Diagnosis d = this.diag;
		this.modalHits.clear();

		if (d == null || !this.modalOpen) {
			return;
		}

		ctx.method_25294(0, 0, w, h, 0xB0000000);

		int bw = Math.min(w - 24, 400);
		int rows = Math.max(1, d.suspects.size());
		int bh = 78 + rows * 34 + 30;
		int bx = (w - bw) / 2;
		int by = Math.max(8, (h - bh) / 2);
		frame(ctx, bx, by, bw, bh, C_HEADER, lighter(C_HEADER, 0.1F), darker(C_HEADER, 0.35F), false);
		ctx.method_25294(bx + 1, by + 1, bx + bw - 1, by + 2, C_WARN);

		text(ctx, "Игра закрылась с ошибкой", bx + 12, by + 12, C_WHITE);
		text(ctx, fit(d.description.isEmpty() ? d.crashFile : d.description, bw - 24), bx + 12, by + 26, C_GRAY);
		text(ctx, d.suspects.isEmpty() ? "Виновника по отчёту определить не удалось." : "Вероятные виновники:", bx + 12, by + 44, C_TEXT);

		int y = by + 60;

		for (CrashDiagnoser.Suspect sp : d.suspects) {
			frame(ctx, bx + 10, y, bw - 20, 30, C_PANEL, lighter(C_PANEL, 0.07F), darker(C_PANEL, 0.3F), false);
			text(ctx, fit(sp.name, bw - 130), bx + 18, y + 6, C_WHITE);
			text(ctx, fit(sp.reason, bw - 130), bx + 18, y + 17, C_GRAY);
			boolean done = this.suspectDone.containsKey(sp.fileName);
			int bxx = bx + bw - 10 - 92;

			if (done) {
				button(ctx, "m-off-" + sp.fileName, bxx, y + 6, 84, 18, "Отключён", null, false, C_DISABLED, C_DISABLED, false, C_GRAY);
			} else {
				button(ctx, "m-off-" + sp.fileName, bxx, y + 6, 84, 18, "Отключить", PixelIcons.POWER, false, C_ACCENT, C_ACCENT_HOVER, inside(mx, my, bxx, y + 6, 84, 18), C_ACCENT_TEXT);
				this.modalHits.add(new Hit("off:" + sp.fileName, null, bxx, y + 6, 84, 18, false));
			}

			y += 34;
		}

		if (d.suspects.isEmpty()) {
			y += 34;
		}

		int fy = by + bh - 28;
		button(ctx, "m-close", bx + bw - 10 - 80, fy, 80, 18, "Закрыть", null, false, C_SECONDARY, C_SECONDARY_HOVER, inside(mx, my, bx + bw - 90, fy, 80, 18), C_TEXT);
		this.modalHits.add(new Hit("close", null, bx + bw - 90, fy, 80, 18, false));
		button(ctx, "m-report", bx + 10, fy, 110, 18, "Открыть отчёт", null, false, C_SECONDARY, C_SECONDARY_HOVER, inside(mx, my, bx + 10, fy, 110, 18), C_TEXT);
		this.modalHits.add(new Hit("report", null, bx + 10, fy, 110, 18, false));
	}
}
