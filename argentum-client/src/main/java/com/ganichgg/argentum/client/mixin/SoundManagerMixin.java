package com.ganichgg.argentum.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ganichgg.argentum.client.handoff.Handoff;

import net.minecraft.class_1113;
import net.minecraft.class_1144;

/** Пока фоновая копия игры скрыта, она молчит: иначе музыка меню играла бы поверх основной игры. class_1144 = SoundManager. */
@Mixin(class_1144.class)
public abstract class SoundManagerMixin {
	// SoundManager.play(SoundInstance)
	@Inject(method = "method_4873", at = @At("HEAD"), cancellable = true)
	private void argentum$silentWhileHidden(class_1113 sound, CallbackInfo ci) {
		if (Handoff.isHiddenChild()) {
			ci.cancel();
		}
	}
}
