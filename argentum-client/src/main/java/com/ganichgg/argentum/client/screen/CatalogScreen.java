package com.ganichgg.argentum.client.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.modrinth.DependencyResolver;
import net.fabricmc.loader.impl.modrinth.InstalledModsIndex;
import net.fabricmc.loader.impl.modrinth.ModInstaller;
import net.fabricmc.loader.impl.modrinth.ModrinthClient;
import net.fabricmc.loader.impl.modrinth.ModrinthProject;
import net.fabricmc.loader.impl.modrinth.ModrinthVersion;
import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_342;
import net.minecraft.class_4185;
import net.minecraft.class_437;

/**
 * Экран каталога модов: поиск на Modrinth, установка с автоматическими зависимостями, список установленных.
 * Вся сетевая работа — в фоновом потоке, экран только показывает состояние.
 *
 * <p>Intermediary (Yarn): class_437 = Screen, class_332 = DrawContext, class_342 = TextFieldWidget,
 * class_4185 = ButtonWidget, class_2561 = Text. Методы: method_25426 = init, method_25394 = render,
 * method_25419 = close, method_25402 = mouseClicked, method_25401 = mouseScrolled, method_25404 = keyPressed,
 * method_37063 = addDrawableChild, field_22787 = client, field_22793 = textRenderer, field_22789/22790 = width/height.
 */
public final class CatalogScreen extends class_437 {
	private static final String LOADER = "fabric";
	private static final int ROW_HEIGHT = 26;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "argentum-catalog");
		t.setDaemon(true);
		return t;
	});

	/** Строка списка: либо найденный на Modrinth проект, либо установленный мод. */
	private static final class Row {
		final String title;
		final String subtitle;
		final ModrinthProject project; // null для установленных
		final String projectId;
		final String fileName; // только для установленных

		Row(String title, String subtitle, ModrinthProject project, String projectId, String fileName) {
			this.title = title;
			this.subtitle = subtitle;
			this.project = project;
			this.projectId = projectId;
			this.fileName = fileName;
		}
	}

	private final class_437 parent;
	private final String gameVersion;

	private class_342 searchField;
	private class_4185 actionButton;
	private class_4185 tabButton;

	private volatile List<Row> rows = Collections.emptyList();
	private volatile String status = "Введите название мода и нажмите «Найти»";
	private volatile int statusColor = GRAY;
	private volatile boolean busy;
	private volatile boolean showInstalled;
	private volatile int selected = -1;
	private volatile int scroll;

	public CatalogScreen(class_437 parent) {
		super(class_2561.method_43470("Моды Argentum"));
		this.parent = parent;
		this.gameVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.21.1");
	}

	private static class_2561 text(String s) {
		return class_2561.method_43470(s);
	}

	@Override
	protected void method_25426() {
		int w = this.field_22789;
		int h = this.field_22790;

		this.searchField = new class_342(this.field_22793, 20, 28, w - 40 - 230, 20, text(""));
		this.searchField.method_47404(text("Поиск модов на Modrinth..."));
		this.searchField.method_1880(80);
		this.method_37063(this.searchField);

		this.method_37063(class_4185.method_46430(text("Найти"), b -> startSearch()).method_46434(w - 20 - 220, 28, 70, 20).method_46431());
		this.tabButton = this.method_37063(class_4185.method_46430(text("Установленные"), b -> toggleTab()).method_46434(w - 20 - 145, 28, 145, 20).method_46431());
		this.actionButton = this.method_37063(class_4185.method_46430(text("Установить"), b -> doAction()).method_46434(w - 20 - 150, h - 52, 150, 20).method_46431());
		this.method_37063(class_4185.method_46430(text("Назад"), b -> this.method_25419()).method_46434(w / 2 - 50, h - 26, 100, 20).method_46431());

		this.actionButton.field_22763 = false; // active
	}

	@Override
	public void method_25419() {
		this.field_22787.method_1507(this.parent); // setScreen(parent)
	}

	private int listWidth() {
		return (int) ((this.field_22789 - 40) * 0.58);
	}

	private int listTop() {
		return 58;
	}

	private int listBottom() {
		return this.field_22790 - 34;
	}

	private void setStatus(String s, int color) {
		this.status = s;
		this.statusColor = color;
	}

	private void toggleTab() {
		if (this.busy) {
			return;
		}

		this.showInstalled = !this.showInstalled;
		this.selected = -1;
		this.scroll = 0;

		if (this.showInstalled) {
			this.tabButton.method_25355(text("Поиск")); // setMessage
			this.actionButton.method_25355(text("Удалить"));
			loadInstalled();
		} else {
			this.tabButton.method_25355(text("Установленные"));
			this.actionButton.method_25355(text("Установить"));
			this.rows = Collections.emptyList();
			setStatus("Введите название мода и нажмите «Найти»", GRAY);
		}

		this.actionButton.field_22763 = false;
	}

	private void loadInstalled() {
		InstalledModsIndex index = InstalledModsIndex.load(FabricLoader.getInstance().getGameDir().resolve("mods"));
		List<Row> list = new ArrayList<>();

		for (Map.Entry<String, InstalledModsIndex.Entry> e : index.entries().entrySet()) {
			list.add(new Row(e.getValue().fileName, "Modrinth: " + e.getKey(), null, e.getKey(), e.getValue().fileName));
		}

		this.rows = list;
		setStatus(list.isEmpty() ? "Через каталог пока ничего не установлено" : "Установлено через каталог: " + list.size(), GRAY);
	}

	private void startSearch() {
		String query = this.searchField.method_1882().trim();

		if (query.isEmpty() || this.busy) {
			return;
		}

		if (this.showInstalled) {
			toggleTab();
		}

		this.busy = true;
		this.selected = -1;
		this.scroll = 0;
		setStatus("Ищу «" + query + "»...", GRAY);

		WORKER.submit(() -> {
			try {
				InstalledModsIndex index = InstalledModsIndex.load(FabricLoader.getInstance().getGameDir().resolve("mods"));
				List<Row> found = new ArrayList<>();

				for (ModrinthProject p : ModrinthClient.search(query, LOADER, this.gameVersion)) {
					String sub = (index.contains(p.id) ? "[установлен] " : "") + (p.description == null ? "" : p.description);
					found.add(new Row(p.title, sub, p, p.id, null));
				}

				this.rows = found;
				setStatus(found.isEmpty() ? "Ничего не найдено под " + this.gameVersion : "Найдено: " + found.size(), found.isEmpty() ? RED : GRAY);
			} catch (Exception e) {
				setStatus("Ошибка: " + e.getMessage(), RED);
			} finally {
				this.busy = false;
			}
		});
	}

	private void doAction() {
		int sel = this.selected;
		List<Row> current = this.rows;

		if (this.busy || sel < 0 || sel >= current.size()) {
			return;
		}

		Row row = current.get(sel);

		if (this.showInstalled) {
			removeInstalled(row);
		} else {
			install(row);
		}
	}

	private void install(Row row) {
		this.busy = true;
		setStatus("Устанавливаю «" + row.title + "» и его зависимости...", GRAY);

		WORKER.submit(() -> {
			try {
				java.nio.file.Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
				InstalledModsIndex index = InstalledModsIndex.load(modsDir);
				List<ModrinthVersion> plan = DependencyResolver.resolve(row.projectId, LOADER, this.gameVersion, index);

				if (plan.isEmpty()) {
					setStatus("«" + row.title + "» уже установлен либо нет версии под " + this.gameVersion, RED);
					return;
				}

				ModInstaller.InstallResult result = ModInstaller.install(modsDir, plan);
				setStatus("Установлено: " + String.join(", ", result.installedModNames) + ". Заработает после перезапуска игры.", GREEN);
			} catch (Exception e) {
				setStatus("Ошибка установки: " + e.getMessage(), RED);
			} finally {
				this.busy = false;
			}
		});
	}

	private void removeInstalled(Row row) {
		java.nio.file.Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");

		try {
			Files.deleteIfExists(modsDir.resolve(row.fileName));
		} catch (IOException e) {
			// Windows не даёт удалить jar, который игра сейчас держит открытым
			setStatus("Не удалось удалить " + row.fileName + " — файл занят игрой. Закрой игру и удали его вручную.", RED);
			return;
		}

		InstalledModsIndex index = InstalledModsIndex.load(modsDir);
		index.remove(row.projectId);

		try {
			index.save();
		} catch (IOException e) {
			setStatus("Файл удалён, но реестр не обновился: " + e.getMessage(), RED);
			return;
		}

		this.selected = -1;
		this.actionButton.field_22763 = false;
		loadInstalled();
		setStatus("Удалено: " + row.fileName, GREEN);
	}

	@Override
	public boolean method_25402(double mouseX, double mouseY, int button) {
		if (super.method_25402(mouseX, mouseY, button)) {
			return true;
		}

		int x = 20;
		int top = listTop();

		if (button == 0 && mouseX >= x && mouseX < x + listWidth() && mouseY >= top && mouseY < listBottom()) {
			int index = (int) ((mouseY - top) / ROW_HEIGHT) + this.scroll;

			if (index >= 0 && index < this.rows.size()) {
				this.selected = index;
				this.actionButton.field_22763 = !this.busy;
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean method_25401(double mouseX, double mouseY, double horizontal, double vertical) {
		int visible = (listBottom() - listTop()) / ROW_HEIGHT;
		int maxScroll = Math.max(0, this.rows.size() - visible);
		this.scroll = Math.max(0, Math.min(maxScroll, this.scroll - (int) Math.signum(vertical)));

		return true;
	}

	@Override
	public boolean method_25404(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == 257 || keyCode == 335) && this.searchField.method_25370()) { // Enter, поле в фокусе
			startSearch();
			return true;
		}

		return super.method_25404(keyCode, scanCode, modifiers);
	}

	@Override
	public void method_25394(class_332 ctx, int mouseX, int mouseY, float delta) {
		int w = this.field_22789;
		int h = this.field_22790;

		ctx.method_25294(0, 0, w, h, 0xE6101014); // fill — тёмная подложка
		ctx.method_25300(this.field_22793, "Моды Argentum — каталог Modrinth для " + this.gameVersion, w / 2, 10, WHITE);

		int x = 20;
		int top = listTop();
		int bottom = listBottom();
		int lw = listWidth();

		ctx.method_25294(x, top, x + lw, bottom, 0x66000000);
		ctx.method_44379(x, top, x + lw, bottom); // enableScissor

		List<Row> current = this.rows;
		int visible = (bottom - top) / ROW_HEIGHT + 1;

		for (int i = 0; i < visible; i++) {
			int index = i + this.scroll;

			if (index >= current.size()) {
				break;
			}

			Row row = current.get(index);
			int y = top + i * ROW_HEIGHT;

			if (index == this.selected) {
				ctx.method_25294(x, y, x + lw, y + ROW_HEIGHT, 0x663366CC);
			} else if (mouseX >= x && mouseX < x + lw && mouseY >= y && mouseY < y + ROW_HEIGHT) {
				ctx.method_25294(x, y, x + lw, y + ROW_HEIGHT, 0x33FFFFFF);
			}

			ctx.method_25303(this.field_22793, trim(row.title, lw - 8), x + 4, y + 3, WHITE);
			ctx.method_25303(this.field_22793, trim(row.subtitle, lw - 8), x + 4, y + 14, GRAY);
		}

		ctx.method_44380(); // disableScissor

		// Панель сведений о выбранном
		int dx = x + lw + 12;
		int dw = w - 20 - dx;
		int sel = this.selected;

		if (sel >= 0 && sel < current.size()) {
			Row row = current.get(sel);
			ctx.method_25303(this.field_22793, trim(row.title, dw), dx, top, WHITE);
			int y = top + 14;

			for (String line : wrap(row.subtitle, dw)) {
				if (y > bottom - 40) {
					break;
				}

				ctx.method_25303(this.field_22793, line, dx, y, GRAY);
				y += 11;
			}
		}

		ctx.method_25303(this.field_22793, trim(this.status, w - 40), 20, h - 52 + 6, this.statusColor);

		super.method_25394(ctx, mouseX, mouseY, delta); // кнопки и поле поиска поверх
	}

	private String trim(String s, int maxWidth) {
		if (s == null) {
			return "";
		}

		return this.field_22793.method_1727(s) <= maxWidth ? s : this.field_22793.method_27523(s, maxWidth - this.field_22793.method_1727("...")) + "...";
	}

	private List<String> wrap(String s, int maxWidth) {
		List<String> lines = new ArrayList<>();

		if (s == null) {
			return lines;
		}

		StringBuilder cur = new StringBuilder();

		for (String word : s.split(" ")) {
			String candidate = cur.length() == 0 ? word : cur + " " + word;

			if (this.field_22793.method_1727(candidate) > maxWidth && cur.length() > 0) {
				lines.add(cur.toString());
				cur = new StringBuilder(word);
			} else {
				cur = new StringBuilder(candidate);
			}
		}

		if (cur.length() > 0) {
			lines.add(cur.toString());
		}

		return lines;
	}
}
