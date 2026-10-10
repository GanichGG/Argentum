package com.ganichgg.argentum.client.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.ganichgg.argentum.client.handoff.Handoff;

import net.minecraft.class_1041;
import net.minecraft.class_543;

/**
 * Фоновая копия игры (бесшовное применение модов) создаёт окно скрытым и оконным: ни мигания, ни смены видеорежима,
 * пока копия не готова. Полноэкранный режим включается уже при показе.
 *
 * <p>class_1041 = Window, class_543 = WindowSettings (field_3283 = fullscreen).
 */
@Mixin(class_1041.class)
public abstract class WindowMixin {
	@Redirect(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/class_543;field_3283:Z", opcode = 180))
	private boolean argentum$windowedWhileHidden(class_543 settings) {
		return !Handoff.isHiddenChild() && settings.field_3283;
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
	private long argentum$createHidden(int width, int height, CharSequence title, long monitor, long share) {
		if (Handoff.isHiddenChild()) {
			GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		}

		return GLFW.glfwCreateWindow(width, height, title, monitor, share);
	}

	// Window.toggleFullscreen: игра сама включает полный экран при старте, если так записано в настройках, — а
	// эксклюзивный полноэкранный режим скрыть нельзя, окно фоновой копии вылезло бы поверх основной игры.
	// Полный экран включается в Handoff при показе.
	@org.spongepowered.asm.mixin.injection.Inject(method = "method_4500", at = @At("HEAD"), cancellable = true)
	private void argentum$noFullscreenWhileHidden(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (Handoff.isHiddenChild()) {
			ci.cancel();
		}
	}
}
