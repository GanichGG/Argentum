/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package com.ganichgg.argentum.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ganichgg.argentum.client.screen.CatalogScreen;

import net.minecraft.class_2561;
import net.minecraft.class_364;
import net.minecraft.class_8021;
import net.minecraft.class_4185;
import net.minecraft.class_437;
import net.minecraft.class_442;

/**
 * Кнопка «Моды Argentum» в углу главного меню — открывает каталог модов.
 *
 * <p>class_442 = TitleScreen, class_437 = Screen, class_4185 = ButtonWidget, class_2561 = Text.
 */
@Mixin(class_442.class)
public abstract class TitleScreenMixin extends class_437 {
	protected TitleScreenMixin(class_2561 title) {
		super(title);
	}

	// TitleScreen.init()
	@Inject(method = "method_25426", at = @At("TAIL"))
	private void argentum$addCatalogButton(CallbackInfo ci) {
		// Ванильная раскладка: Singleplayer / Multiplayer / Realms идут через 24 от baseY, ниже — ряд Options.
		int baseY = this.field_22790 / 4 + 48;

		// Освобождаем место, как это делает ModMenu: три верхние кнопки центральной колонки чуть выше
		// (class_8021 = Widget; method_46427 getY, method_46419 setY, method_25368 getWidth).
		for (class_364 child : this.method_25396()) {
			if (child instanceof class_8021 widget && widget.method_25368() == 200
					&& widget.method_46427() >= baseY && widget.method_46427() <= baseY + 48) {
				widget.method_46419(widget.method_46427() - 12);
			}
		}

		// Наша кнопка — в той же колонке сразу под Realms: baseY + 36 (новый Y Realms) + 24
		this.method_37063(class_4185.method_46430(class_2561.method_43470("Моды Argentum"),
				button -> this.field_22787.method_1507(new CatalogScreen(this))).method_46434(this.field_22789 / 2 - 100, baseY + 60, 200, 20).method_46431());

		com.ganichgg.argentum.client.handoff.Handoff.childReady(this.field_22787);
		com.ganichgg.argentum.client.DevHooks.runHotloadTest();
		com.ganichgg.argentum.client.DevHooks.runHandoffTest(this.field_22787);

		// Для разработки: файл .argentum-open-catalog в папке игры открывает каталог сразу при старте (и удаляется) —
		// удобно снимать скриншоты без ввода. Переменная окружения не годится: Prism уже запущен и игре её не передаст.
		java.nio.file.Path flag = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(".argentum-open-catalog");

		if (java.nio.file.Files.exists(flag)) {
			try {
				java.nio.file.Files.delete(flag);
			} catch (java.io.IOException ignored) {
				// неважно
			}

			this.field_22787.method_1507(new CatalogScreen(this).enableDevTimeline());
		}
	}

	// Скрытая фоновая копия игры ждёт здесь команды «показаться» (бесшовное применение модов)
	@Inject(method = "method_25394", at = @At("HEAD"))
	private void argentum$handoffPoll(net.minecraft.class_332 ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		java.util.Properties go = com.ganichgg.argentum.client.handoff.Handoff.childPoll(this.field_22787);

		if (go != null && Boolean.parseBoolean(go.getProperty("openCatalog"))) {
			this.field_22787.method_1507(new CatalogScreen(this).afterApply(go.getProperty("message", "")));
		}

		// моды, которые не запустились и были отключены, — сразу показываем, не дожидаясь, пока игрок откроет каталог
		else if (go == null && !com.ganichgg.argentum.client.handoff.Handoff.isHiddenChild() && CatalogScreen.failuresPending()) {
			this.field_22787.method_1507(new CatalogScreen(this));
		}
	}
}
