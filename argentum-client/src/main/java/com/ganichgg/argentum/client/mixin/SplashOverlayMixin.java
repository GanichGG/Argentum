package com.ganichgg.argentum.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.class_156;
import net.minecraft.class_310;
import net.minecraft.class_332;
import net.minecraft.class_3532;
import net.minecraft.class_425;

/**
 * Надпись над логотипом Mojang на красном экране загрузки.
 *
 * <p>Написано сразу в intermediary-именах (в рантайме игра именно в них, рефмап не нужен), а не в Yarn —
 * соответствие: class_425 = SplashOverlay, class_310 = MinecraftClient, class_332 = DrawContext,
 * class_156 = Util, class_3532 = MathHelper.
 */
@Mixin(class_425.class)
public abstract class SplashOverlayMixin {
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
		int width = context.method_51421(); // getScaledWindowWidth
		int height = context.method_51443(); // getScaledWindowHeight

		// Та же геометрия, что у ванильного логотипа: верх логотипа = центр экрана - половина его высоты.
		double logoHeight = Math.min(width * 0.75, height) * 0.25;
		int logoTop = (int) (height * 0.5 - logoHeight * 0.5);

		// Шрифт игры применяется только в самом конце загрузки ресурсов: раньше текст рисуется рядом
		// квадратиков-"пустых глифов". Поэтому показываем надпись, начиная с момента завершения загрузки.
		if (this.field_17771 <= -1L) {
			return;
		}

		// Ваниль держит оверлей полностью непрозрачным секунду после конца загрузки, потом гасит за секунду.
		float sinceComplete = (class_156.method_658() - this.field_17771) / 1000.0F; // Util.getMeasuringTimeMs
		float alpha = class_3532.method_15363(2.0F - sinceComplete, 0.0F, 1.0F); // MathHelper.clamp

		int alphaByte = (int) (alpha * 255.0F);

		// У текстового рендера значения альфы <= 3 трактуются как непрозрачные — не рисуем почти прозрачное.
		if (alphaByte <= 4) {
			return;
		}

		int color = (alphaByte << 24) | 0xFFFFFF;
		// DrawContext.drawCenteredTextWithShadow(TextRenderer, String, int, int, int); client.textRenderer = field_1772
		context.method_25300(this.field_18217.field_1772, "Спасибо за использование Argentum", width / 2, logoTop - 16, color);
	}
}
