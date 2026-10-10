package com.ganichgg.argentum.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ganichgg.argentum.client.screen.CatalogScreen;

import net.minecraft.class_2561;
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
		this.method_37063(class_4185.method_46430(class_2561.method_43470("Моды Argentum"),
				button -> this.field_22787.method_1507(new CatalogScreen(this))).method_46434(6, 6, 100, 20).method_46431());
	}
}
