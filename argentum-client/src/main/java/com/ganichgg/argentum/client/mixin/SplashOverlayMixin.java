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

	// SplashOverlay.render(DrawContext, int, int, float)
	@Inject(method = "method_25394", at = @At("TAIL"))
	private void argentum$drawThanks(class_332 context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
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
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
		// DrawContext.drawTexture(Identifier, x, y, u, v, w, h, textureW, textureH)
		context.method_25290(line.texture, (width - line.width) / 2, logoTop - line.height - 8, 0.0F, 0.0F, line.width, line.height, line.width, line.height);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}
}
