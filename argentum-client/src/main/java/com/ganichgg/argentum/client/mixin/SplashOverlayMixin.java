/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

import com.ganichgg.argentum.client.EarlyText;

import net.minecraft.class_156;
import net.minecraft.class_310;
import net.minecraft.class_332;
import net.minecraft.class_3532;
import net.minecraft.class_425;

/**
 * Надпись над логотипом Mojang на красном экране загрузки — с первого кадра, своим шрифтом
 * ({@link EarlyText}), а не шрифтом игры, которого в начале загрузки ещё нет.
 *
 * <p>Написано в intermediary-именах (в рантайме игра именно в них, рефмап не нужен): class_425 = SplashOverlay,
 * class_310 = MinecraftClient, class_332 = DrawContext, class_156 = Util, class_3532 = MathHelper.
 */
@Mixin(class_425.class)
public abstract class SplashOverlayMixin {
	private static final String THANKS = "Спасибо за использование Argentum";

	// MinecraftClient client
	@Shadow
	@Final
	private class_310 field_18217;

	// long reloadCompleteTime
	@Shadow
	private long field_17771;

	// float progress (сглаженный ход загрузки ресурсов, 0..1)
	@Shadow
	private float field_17770;

	// SplashOverlay.render(DrawContext, int, int, float)
	@Inject(method = "method_25394", at = @At("TAIL"))
	private void argentum$drawThanks(class_332 context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		// скрытая фоновая копия игры сообщает основной, как далеко она с загрузкой (полоса «Подготовка модов»)
		com.ganichgg.argentum.client.handoff.Handoff.childReportProgress(this.field_17770, this.field_17771 > -1L);

		EarlyText.Line line = EarlyText.get(this.field_18217, THANKS);

		if (line == null) {
			return;
		}

		int width = context.method_51421(); // getScaledWindowWidth
		int height = context.method_51443(); // getScaledWindowHeight

		// Та же геометрия, что у ванильного логотипа: верх логотипа = центр экрана - половина его высоты.
		double logoHeight = Math.min(width * 0.75, height) * 0.25;
		int logoTop = (int) (height * 0.5 - logoHeight * 0.5);

		// Ваниль держит оверлей полностью непрозрачным секунду после конца загрузки, потом гасит за секунду.
		float alpha = 1.0F;

		if (this.field_17771 > -1L) {
			float sinceComplete = (class_156.method_658() - this.field_17771) / 1000.0F; // Util.getMeasuringTimeMs
			alpha = class_3532.method_15363(2.0F - sinceComplete, 0.0F, 1.0F); // MathHelper.clamp
		}

		if (alpha <= 0.0F) {
			return;
		}

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		drawLine(context, line, (width - line.width) / 2, 10, alpha); // вверху экрана, не над логотипом

		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		argentum$devSplashShot();
	}

	private static void drawLine(class_332 context, EarlyText.Line line, int x, int y, float alpha) {
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
		// DrawContext.drawTexture(Identifier, x, y, u, v, w, h, textureW, textureH)
		context.method_25290(line.texture, x, y, 0.0F, 0.0F, line.width, line.height, line.width, line.height);
	}

	private static long devSplashStart;

	// Dev: если в папке игры лежит .argentum-splash-shot, сохраняем кадр экрана загрузки (без участия окна ОС)
	private void argentum$devSplashShot() {
		java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		java.nio.file.Path flag = dir.resolve(".argentum-splash-shot");

		if (devSplashStart == 0) {
			devSplashStart = System.currentTimeMillis();
		}

		if (System.currentTimeMillis() - devSplashStart < 1500 || !java.nio.file.Files.exists(flag)) {
			return;
		}

		try {
			java.nio.file.Files.delete(flag);
			java.nio.file.Files.createDirectories(dir.resolve(".argentum"));

			try (net.minecraft.class_1011 image = net.minecraft.class_318.method_1663(this.field_18217.method_1522())) {
				image.method_4314(dir.resolve(".argentum").resolve("splash.png"));
			}
		} catch (java.io.IOException | RuntimeException ignored) {
			// неважно
		}
	}
}
