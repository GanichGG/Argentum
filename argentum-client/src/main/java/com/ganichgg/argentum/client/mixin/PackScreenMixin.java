/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.client.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ganichgg.argentum.client.queue.ChangeQueue;
import com.ganichgg.argentum.client.screen.CatalogScreen;
import com.ganichgg.argentum.core.i18n.Lang;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.class_2561;
import net.minecraft.class_4185;
import net.minecraft.class_437;
import net.minecraft.class_5375;

/**
 * Кнопка «Каталог Argentum» на экране ресурспаков игры: открывает каталог сразу на ресурспаках. Скачанный пак появляется в
 * списке «Доступные» сам — экран игры следит за папкой, — его остаётся перенести в «Выбранные».
 *
 * <p>class_5375 = PackScreen (тот же экран служит и для датапаков мира — там кнопки нет), class_4185 = ButtonWidget.
 */
@Mixin(class_5375.class)
public abstract class PackScreenMixin extends class_437 {
	// PackScreen.file — папка паков этого экрана
	@Shadow
	@Final
	private Path field_25474;

	protected PackScreenMixin(class_2561 title) {
		super(title);
	}

	// PackScreen.init()
	@Inject(method = "method_25426", at = @At("TAIL"))
	private void argentum$addCatalogButton(CallbackInfo ci) {
		Path resourcepacks = FabricLoader.getInstance().getGameDir().resolve("resourcepacks").toAbsolutePath().normalize();

		if (this.field_25474 == null || !this.field_25474.toAbsolutePath().normalize().equals(resourcepacks)) {
			return; // датапаки мира
		}

		class_437 self = this;
		String label = Lang.tr("Каталог Argentum");
		int w = Math.max(110, this.field_22793.method_1727(label) + 16);

		// справа вверху, на уровне заголовка: ванильные кнопки внизу не трогаем
		this.method_37063(class_4185.method_46430(class_2561.method_43470(label),
				button -> this.field_22787.method_1507(new CatalogScreen(self).forContent(ChangeQueue.Content.RESOURCEPACKS)))
				.method_46434(this.field_22789 - w - 6, 6, w, 20).method_46431());
	}
}
