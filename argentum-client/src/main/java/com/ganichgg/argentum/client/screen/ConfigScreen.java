/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.client.screen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ganichgg.argentum.client.config.ConfigDoc;
import com.ganichgg.argentum.client.ui.ModConfigs;
import com.ganichgg.argentum.core.i18n.Lang;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import net.minecraft.class_1109;
import net.minecraft.class_2561;
import net.minecraft.class_332;
import net.minecraft.class_3417;
import net.minecraft.class_342;
import net.minecraft.class_437;

/**
 * Встроенный редактор настроек мода: ключи и значения из файла в config/ в виде списка — переключатели для
 * да/нет, поля для чисел и строк, фильтр сверху. Сохраняет прямо в файл (рядом остаётся копия .argentum.bak).
 *
 * <p>Тот же стиль, что у каталога: панорама с размытием, рамки Ore UI, серебряный акцент.
 * class_437 = Screen, class_332 = DrawContext, class_342 = TextFieldWidget.
 */
public final class ConfigScreen extends class_437 {
	private static final int C_HEADER = 0xE6232425;
	private static final int C_PANEL = 0xFF2A2B2C;
	private static final int C_PANEL_HOVER = 0xFF323334;
	private static final int C_FIELD = 0xFF151616;
	private static final int C_BLACK = 0xFF0E0E0F;
	private static final int C_SECONDARY = 0xFF39393B;
	private static final int C_SECONDARY_HOVER = 0xFF47484A;
	private static final int C_ACCENT = 0xFFC9CCD2;
	private static final int C_ACCENT_HOVER = 0xFFE8EAEE;
	private static final int C_ACCENT_TEXT = 0xFF17181A;
	private static final int C_DISABLED = 0xFF2E2F30;
	private static final int C_WHITE = 0xFFFFFFFF;
	private static final int C_TEXT = 0xFFD0D0D6;
	private static final int C_GRAY = 0xFF8E8E98;
	private static final int C_OK = 0xFFE3E6EB;
	private static final int C_WARN = 0xFFFFC857;
	private static final int C_RED = 0xFFFF8A8A;
	private static final int C_DIM = 0x141C1D1D;

	private static final int PAD = 8;
	private static final int ROW_H = 26;
	private static final int GAP = 3;
	private static final int BTN_H = 18;
	private static final int FOOTER_H = 24;
	private static final int CTRL_W = 150;

	private final class_437 parent;
	private final String title;
	private final List<Path> files;
	private int fileIndex;
	private ConfigDoc doc;
	private String loadError;
	private List<ConfigDoc.Entry> visible = new ArrayList<>();

	private class_342 filterField;
	private class_342 editField;
	private ConfigDoc.Entry editing;

	private float scroll;
	private float scrollTarget;
	private long lastFrame;
	private float dt = 0.016F;
	private String status = "";
	private int statusColor = C_GRAY;
	private boolean closeArmed;
	private final Map<String, Float> hover = new HashMap<>();
	private final Map<String, Float> flash = new HashMap<>();

	public ConfigScreen(class_437 parent, String title, List<Path> files) {
		super(class_2561.method_43470(Lang.tr("Настройки: ") + title));
		this.parent = parent;
		this.title = title;
		this.files = new ArrayList<>();

		for (Path p : files) {
			if (ConfigDoc.supports(p)) {
				this.files.add(p);
			}
		}

		load();
	}

	private void load() {
		this.doc = null;
		this.loadError = null;
		this.editing = null;

		if (this.files.isEmpty()) {
			this.loadError = Lang.tr("Нет файлов настроек, которые умеет читать редактор");
			return;
		}

		try {
			this.doc = ConfigDoc.load(this.files.get(this.fileIndex));
		} catch (IOException | RuntimeException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось прочитать настройки " + this.files.get(this.fileIndex), e);
			this.loadError = Lang.tr("Не удалось прочитать файл: ") + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}

		refilter();
	}

	private void refilter() {
		this.visible = new ArrayList<>();

		if (this.doc == null) {
			return;
		}

		String q = this.filterField == null ? "" : this.filterField.method_1882().trim().toLowerCase(Locale.ROOT);

		for (ConfigDoc.Entry e : this.doc.entries()) {
			if (q.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(q) || e.group.toLowerCase(Locale.ROOT).contains(q)) {
				this.visible.add(e);
			}
		}

		this.scroll = 0;
		this.scrollTarget = 0;
	}

	// ---------------------------------------------------------------- раскладка

	private int listTop() {
		return this.files.size() > 1 ? 66 : 42;
	}

	private int listBottom() {
		return this.field_22790 - FOOTER_H;
	}

	private float maxScroll() {
		int n = this.visible.size();
		float content = n == 0 ? 0 : n * (ROW_H + GAP) - GAP;

		return Math.max(0, content - (listBottom() - listTop()));
	}

	private int ctrlX() {
		return this.field_22789 - PAD - 5 - CTRL_W - 6;
	}

	private int rowY(int i) {
		return (int) (listTop() + i * (ROW_H + GAP) - this.scroll);
	}

	// ---------------------------------------------------------------- жизненный цикл

	@Override
	protected void method_25426() {
		int w = this.field_22789;
		this.filterField = new class_342(this.field_22793, PAD + 6, 8 + 8, w - PAD * 2 - 12, 10, class_2561.method_43470(""));
		this.filterField.method_1858(false);
		this.filterField.method_1880(60);
		this.filterField.method_47404(class_2561.method_43470(Lang.tr("Фильтр настроек")));
		this.filterField.method_1863(s -> refilter());
		this.method_37063(this.filterField);

		this.editField = new class_342(this.field_22793, 0, 0, CTRL_W - 8, 10, class_2561.method_43470(""));
		this.editField.method_1858(false);
		this.editField.method_1880(512);
		this.editField.field_22764 = false;
		this.method_37063(this.editField);
	}

	@Override
	public void method_25419() {
		this.field_22787.method_1507(this.parent);
	}

	// ---------------------------------------------------------------- действия

	private void click() {
		this.field_22787.method_1483().method_4873(class_1109.method_47978(class_3417.field_15015, 1.0F));
	}

	private void press(String key) {
		this.flash.put(key, 1F);
		click();
	}

	private void setStatus(String s, int color) {
		this.status = s;
		this.statusColor = color;
	}

	private void startEdit(ConfigDoc.Entry e) {
		commitEdit();
		this.editing = e;
		this.editField.method_1852(e.value);
		this.editField.field_22764 = true;
		this.editField.method_25365(true);
		this.method_25395(this.editField);
		this.closeArmed = false;
	}

	/** Применяет введённое в поле, если оно подходит по типу; иначе оставляет поле открытым. */
	private boolean commitEdit() {
		if (this.editing == null) {
			return true;
		}

		String text = this.editField.method_1882();

		if (!this.editing.valid(text)) {
			setStatus(Lang.tr("Значение «") + this.editing.label + Lang.tr("» не подходит по типу"), C_RED);
			return false;
		}

		this.editing.value = text;
		stopEdit();

		return true;
	}

	private void stopEdit() {
		this.editing = null;
		this.editField.field_22764 = false;
		this.editField.method_25365(false);
		this.method_25395(null);
	}

	private void save() {
		if (this.doc == null || !commitEdit()) {
			return;
		}

		try {
			this.doc.save();
			this.closeArmed = false;
			load();
			setStatus(Lang.tr("Сохранено — вступит в силу после перезапуска"), C_OK);
		} catch (IOException | RuntimeException e) {
			Log.warn(LogCategory.GENERAL, "Не удалось сохранить настройки", e);
			setStatus(Lang.tr("Не удалось сохранить: ") + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), C_RED);
		}
	}

	private void openExternally() {
		if (this.files.isEmpty()) {
			return;
		}

		try {
			ModConfigs.openExternally(this.files.get(this.fileIndex));
			setStatus(Lang.tr("Открываю файл в системе"), C_OK);
		} catch (IOException e) {
			setStatus(Lang.tr("Не удалось открыть файл"), C_RED);
		}
	}

	private void tryClose() {
		if (this.doc != null && this.doc.dirty() && !this.closeArmed) {
			this.closeArmed = true;
			setStatus(Lang.tr("Есть несохранённые изменения — «Назад» ещё раз, чтобы выйти"), C_WARN);
			return;
		}

		this.method_25419();
	}

	// ---------------------------------------------------------------- ввод

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private int[] footerButton(int index) { // справа налево: Назад, Файл, Сбросить, Сохранить
		int[] widths = {56, 56, 76, 84};
		int x = this.field_22789 - PAD;

		for (int i = 0; i <= index; i++) {
			x -= widths[i] + (i > 0 ? 4 : 0);
		}

		return new int[] {x, this.field_22790 - FOOTER_H + 3, widths[index], BTN_H};
	}

	@Override
	public boolean method_25402(double mx, double my, int button) {
		if (button != 0) {
			return super.method_25402(mx, my, button);
		}

		int w = this.field_22789;

		if (this.editing != null && this.editField.field_22764 && inside(mx, my, this.editField.method_46426() - 3, this.editField.method_46427() - 6, CTRL_W, ROW_H - 2)) {
			return super.method_25402(mx, my, button);
		}

		if (inside(mx, my, PAD, 8, w - PAD * 2, 24)) {
			commitEdit();
			return super.method_25402(mx, my, button);
		}

		commitEdit();

		String[] names = {"back", "file", "reset", "save"};

		for (int i = 0; i < 4; i++) {
			int[] r = footerButton(i);

			if (inside(mx, my, r[0], r[1], r[2], r[3])) {
				press(names[i]);

				switch (i) {
				case 0: tryClose(); break;
				case 1: openExternally(); break;
				case 2: load(); this.closeArmed = false; setStatus(Lang.tr("Изменения сброшены"), C_GRAY); break;
				default: save();
				}

				return true;
			}
		}

		if (this.files.size() > 1 && my >= 38 && my < 38 + BTN_H) {
			int x = PAD;

			for (int i = 0; i < this.files.size(); i++) {
				int bw = textWidth(shortName(this.files.get(i))) + 16;

				if (inside(mx, my, x, 38, bw, BTN_H)) {
					if (i != this.fileIndex) {
						if (this.doc != null && this.doc.dirty()) {
							setStatus(Lang.tr("Сначала сохрани или сбрось изменения"), C_WARN);
						} else {
							press("file" + i);
							this.fileIndex = i;
							load();
						}
					}

					return true;
				}

				x += bw + 4;
			}
		}

		if (my >= listTop() && my < listBottom()) {
			for (int i = 0; i < this.visible.size(); i++) {
				int y = rowY(i);

				if (my >= y && my < y + ROW_H && mx >= PAD) {
					ConfigDoc.Entry e = this.visible.get(i);

					if (mx >= ctrlX()) {
						if (e.kind == ConfigDoc.Kind.BOOL) {
							click();
							e.value = e.value.equals("true") ? "false" : "true";
							this.closeArmed = false;
						} else {
							click();
							startEdit(e);
						}
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
		if (keyCode == 256) { // Esc
			if (this.editing != null) {
				stopEdit();
			} else {
				tryClose();
			}

			return true;
		}

		if ((keyCode == 257 || keyCode == 335) && this.editing != null) { // Enter
			commitEdit();
			return true;
		}

		return super.method_25404(keyCode, scanCode, modifiers);
	}

	// ---------------------------------------------------------------- отрисовка

	private int textWidth(String s) {
		return this.field_22793.method_1727(s);
	}

	private void text(class_332 ctx, String s, int x, int y, int color) {
		ctx.method_51433(this.field_22793, s, x, y, color, false);
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

	private static String shortName(Path p) {
		return p.getFileName().toString();
	}

	private static int lerpColor(int a, int b, float t) {
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);

		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	private static int lighter(int c, float t) {
		return lerpColor(c, C_WHITE, t);
	}

	private static int darker(int c, float t) {
		return lerpColor(c, 0xFF000000, t);
	}

	private static float approach(float cur, float target, float dt, float speed) {
		float next = cur + (target - cur) * (1 - (float) Math.exp(-dt * speed));

		return Math.abs(target - next) < 0.004F ? target : next;
	}

	/** Рамка в духе Ore UI: чёрный контур, светлая обводка, тёмная нижняя полоса 2 px. */
	private static void frame(class_332 ctx, int x, int y, int w, int h, int base, int border, int bottom, boolean pressed) {
		ctx.method_25294(x, y, x + w, y + h, C_BLACK);
		ctx.method_25294(x + 1, y + 1, x + w - 1, y + h - 1, base);
		int strip = pressed ? 0 : 2;

		if (strip > 0) {
			ctx.method_25294(x + 1, y + h - 1 - strip, x + w - 1, y + h - 1, bottom);
		}

		int rb = y + h - 1 - strip;
		ctx.method_25294(x + 1, y + 1, x + w - 1, y + 2, border);
		ctx.method_25294(x + 1, rb - 1, x + w - 1, rb, border);
		ctx.method_25294(x + 1, y + 1, x + 2, rb, border);
		ctx.method_25294(x + w - 2, y + 1, x + w - 1, rb, border);
	}

	private float anim(String key, boolean on) {
		float v = approach(this.hover.getOrDefault(key, 0F), on ? 1F : 0F, this.dt, 16);
		this.hover.put(key, v);

		return v;
	}

	private void button(class_332 ctx, String key, int x, int y, int w, int h, String label, int base, int hoverColor, boolean hovered, int textColor) {
		float k = anim(key, hovered);
		boolean pressed = this.flash.getOrDefault(key, 0F) > 0.55F;
		int body = lerpColor(base, hoverColor, k);
		boolean accent = base == C_ACCENT;
		frame(ctx, x, y, w, h, body, lighter(body, accent ? 0.26F : 0.08F + 0.1F * k), darker(body, accent ? 0.55F : 0.28F), pressed);
		String s = fit(label, w - 8);
		text(ctx, s, x + (w - textWidth(s)) / 2, y + (h - 2) / 2 - 4 + (pressed ? 1 : 0), textColor);
	}

	@Override
	public void method_25394(class_332 ctx, int mx, int my, float delta) {
		super.method_25394(ctx, mx, my, delta);
		devScenario();
	}

	private long devStart;
	private int devStep;

	/** Dev: если в папке игры лежит .argentum-config-dev, редактор сам делает снимки и «нажимает» поля — для проверки без ввода. */
	private void devScenario() {
		if (!java.nio.file.Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(".argentum-config-dev"))) {
			return;
		}

		if (this.devStart == 0) {
			this.devStart = System.currentTimeMillis();
		}

		long t = System.currentTimeMillis() - this.devStart;

		if (this.devStep == 0 && t > 1500) {
			com.ganichgg.argentum.client.DevHooks.dumpFrame(this.field_22787, "cfg1");
			this.devStep = 1;
		} else if (this.devStep == 1 && t > 2200) {
			for (ConfigDoc.Entry e : this.visible) {
				if (e.kind != ConfigDoc.Kind.BOOL) {
					startEdit(e);
					break;
				}
			}

			for (ConfigDoc.Entry e : this.visible) {
				if (e.kind == ConfigDoc.Kind.BOOL) {
					e.value = e.value.equals("true") ? "false" : "true"; // изменённая настройка получает метку
					break;
				}
			}

			this.devStep = 2;
		} else if (this.devStep == 2 && t > 3500) {
			com.ganichgg.argentum.client.DevHooks.dumpFrame(this.field_22787, "cfg2");
			this.devStep = 3;
		}
	}

	@Override
	public void method_25420(class_332 ctx, int mx, int my, float delta) {
		long now = System.nanoTime();
		this.dt = this.lastFrame == 0 ? 0.016F : Math.min(0.1F, (now - this.lastFrame) / 1_000_000_000F);
		this.lastFrame = now;
		this.scrollTarget = Math.max(0, Math.min(maxScroll(), this.scrollTarget));
		this.scroll = approach(this.scroll, this.scrollTarget, this.dt, 16);
		this.flash.replaceAll((k, v) -> v - this.dt * 4F);
		this.flash.values().removeIf(v -> v <= 0F);

		int w = this.field_22789;
		int h = this.field_22790;

		super.method_25420(ctx, mx, my, delta); // панорама с ванильным размытием
		ctx.method_25294(0, 0, w, h, C_DIM);

		// шапка: фильтр и (если файлов несколько) вкладки файлов
		ctx.method_25294(0, 0, w, listTop() - 2, C_HEADER);
		ctx.method_25294(0, listTop() - 2, w, listTop() - 1, C_BLACK);
		boolean filterFocus = this.filterField.method_25370();
		frame(ctx, PAD, 8, w - PAD * 2, 24, C_FIELD, lighter(C_FIELD, filterFocus ? 0.3F : 0.14F), darker(C_FIELD, 0.4F), false);

		if (this.files.size() > 1) {
			int x = PAD;

			for (int i = 0; i < this.files.size(); i++) {
				String name = shortName(this.files.get(i));
				int bw = textWidth(name) + 16;
				boolean active = i == this.fileIndex;
				button(ctx, "file" + i, x, 38, bw, BTN_H, name, active ? C_ACCENT : C_SECONDARY, active ? C_ACCENT_HOVER : C_SECONDARY_HOVER,
						inside(mx, my, x, 38, bw, BTN_H), active ? C_ACCENT_TEXT : C_TEXT);
				x += bw + 4;
			}
		}

		drawRows(ctx, mx, my, w);
		drawFooter(ctx, mx, my, w, h);
	}

	private void drawRows(class_332 ctx, int mx, int my, int w) {
		int top = listTop();
		int bottom = listBottom();

		if (this.loadError != null || this.visible.isEmpty()) {
			String msg = this.loadError != null ? this.loadError : this.doc != null && this.doc.entries().isEmpty() ? Lang.tr("В файле нет настроек") : Lang.tr("Ничего не найдено");
			text(ctx, fit(msg, w - PAD * 4), (w - Math.min(textWidth(msg), w - PAD * 4)) / 2, top + (bottom - top) / 2 - 4, C_GRAY);
			this.editField.field_22764 = false;

			return;
		}

		ctx.method_44379(0, top, w, bottom);
		boolean editShown = false;
		int cx = ctrlX();

		for (int i = 0; i < this.visible.size(); i++) {
			int y = rowY(i);

			if (y + ROW_H < top) {
				continue;
			}

			if (y >= bottom) {
				break;
			}

			ConfigDoc.Entry e = this.visible.get(i);
			boolean hovered = inside(mx, my, PAD, y, w - PAD * 2, ROW_H) && my >= top && my < bottom;
			float k = anim("row" + System.identityHashCode(e), hovered);
			int base = lerpColor(C_PANEL, C_PANEL_HOVER, k);
			frame(ctx, PAD, y, w - PAD * 2 - 6, ROW_H, base, lighter(base, 0.07F + 0.06F * k), darker(base, 0.3F), false);

			if (e.dirty()) { // изменённая настройка помечена светлой полоской слева
				ctx.method_25294(PAD + 2, y + 3, PAD + 4, y + ROW_H - 5, C_ACCENT);
			}

			int labelX = PAD + 10;
			int labelMax = cx - labelX - 8;
			String label = fit(e.label, labelMax);
			text(ctx, label, labelX, e.group.isEmpty() ? y + (ROW_H - 2 - 8) / 2 : y + 5, C_WHITE);

			if (!e.group.isEmpty()) {
				text(ctx, fit(e.group, labelMax), labelX, y + 15, C_GRAY);
			}

			// поле значения
			boolean isEdit = e == this.editing;

			if (e.kind == ConfigDoc.Kind.BOOL) {
				boolean on = e.value.equals("true");
				button(ctx, "val" + System.identityHashCode(e), cx, y + 3, CTRL_W, ROW_H - 6 + 1, on ? Lang.tr("Включено") : Lang.tr("Выключено"),
						on ? C_ACCENT : C_SECONDARY, on ? C_ACCENT_HOVER : C_SECONDARY_HOVER, inside(mx, my, cx, y + 3, CTRL_W, ROW_H - 5) && hovered,
						on ? C_ACCENT_TEXT : C_TEXT);
			} else {
				boolean ok = !isEdit || e.valid(this.editField.method_1882());
				int border = !ok ? C_RED : isEdit ? 0xFF6A6B6D : lighter(C_FIELD, 0.14F);
				frame(ctx, cx, y + 3, CTRL_W, ROW_H - 5, C_FIELD, border, darker(C_FIELD, 0.4F), false);

				if (isEdit) {
					if (y >= top && y + ROW_H <= bottom) {
						this.editField.method_46421(cx + 5);
						this.editField.method_46419(y + 3 + (ROW_H - 5 - 2 - 8) / 2 + 1);
						this.editField.field_22764 = true;
						editShown = true;
					}
				} else {
					text(ctx, fit(e.value, CTRL_W - 10), cx + 5, y + 3 + (ROW_H - 5 - 2 - 8) / 2 + 1, e.dirty() ? C_OK : C_TEXT);
				}
			}
		}

		if (this.editing != null && !editShown) {
			this.editField.field_22764 = false; // строка ушла за край списка — поле прячем, текст остаётся
		}

		ctx.method_44380();

		float max = maxScroll();

		if (max > 0) {
			int trackH = bottom - top;
			int thumbH = Math.max(14, (int) (trackH * (trackH / (trackH + max))));
			int thumbY = top + (int) ((trackH - thumbH) * (this.scroll / max));
			ctx.method_25294(w - PAD - 4, top, w - PAD, bottom, C_BLACK);
			ctx.method_25294(w - PAD - 3, thumbY + 1, w - PAD - 1, thumbY + thumbH - 1, C_SECONDARY_HOVER);
		}
	}

	private void drawFooter(class_332 ctx, int mx, int my, int w, int h) {
		int top = h - FOOTER_H;
		ctx.method_25294(0, top, w, h, C_HEADER);
		ctx.method_25294(0, top, w, top + 1, C_BLACK);

		boolean dirty = this.doc != null && this.doc.dirty();
		String[] labels = {Lang.tr("Назад"), Lang.tr("Файл"), Lang.tr("Сбросить"), Lang.tr("Сохранить")};
		String[] keys = {"back", "file", "reset", "save"};

		for (int i = 0; i < 4; i++) {
			int[] r = footerButton(i);
			boolean enabled = i < 2 || (this.doc != null && (i == 2 ? dirty : dirty));
			boolean save = i == 3;
			boolean hovered = enabled && inside(mx, my, r[0], r[1], r[2], r[3]);

			if (!enabled) {
				button(ctx, keys[i], r[0], r[1], r[2], r[3], labels[i], C_DISABLED, C_DISABLED, false, 0xFF6A6B6D);
			} else if (save) {
				button(ctx, keys[i], r[0], r[1], r[2], r[3], labels[i], C_ACCENT, C_ACCENT_HOVER, hovered, C_ACCENT_TEXT);
			} else {
				button(ctx, keys[i], r[0], r[1], r[2], r[3], labels[i], C_SECONDARY, C_SECONDARY_HOVER, hovered, C_TEXT);
			}
		}

		String line = this.status;
		int color = this.statusColor;

		if (line.isEmpty()) {
			line = this.doc == null ? "" : shortName(this.files.get(this.fileIndex)) + (dirty ? Lang.tr(" · есть изменения") : "");

			if (dirty && this.doc.lossy()) {
				line = Lang.tr("Комментарии в файле пропадут при сохранении");
				color = C_WARN;
			}
		}

		int left = footerButton(3)[0] - 10;
		text(ctx, fit(line, left - PAD), PAD, top + 8, color);
	}
}
