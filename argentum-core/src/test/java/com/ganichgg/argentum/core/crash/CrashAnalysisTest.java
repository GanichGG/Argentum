/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.core.crash;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CrashAnalysisTest {
	private static final String MIXIN_REPORT = "---- Minecraft Crash Report ----\n"
			+ "// Who set us up the TNT?\n\n"
			+ "Time: 2026-10-29 12:00:00\n"
			+ "Description: Initializing game\n\n"
			+ "java.lang.RuntimeException: org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered\n"
			+ "\tat net.minecraft.class_310.<init>(class_310.java:458)\n"
			+ "\tat net.minecraft.client.main.Main.main(Main.java:211)\n"
			+ "Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered\n"
			+ "\tat org.spongepowered.asm.mixin.transformer.MixinProcessor.applyMixins(MixinProcessor.java:392)\n"
			+ "\t... 12 more\n"
			+ "Caused by: org.spongepowered.asm.mixin.injection.throwables.InjectionError: Critical injection failure: @Inject annotation on onRender could not find any targets"
			+ " matching 'render' in net.minecraft.class_329. [PREINJECT Applicator Phase -> fancyhud.mixins.json:GuiMixin from mod fancyhud -> Prepare Injections]\n"
			+ "\tat org.spongepowered.asm.mixin.injection.struct.InjectionInfo.validateTargets(InjectionInfo.java:656)\n"
			+ "\t... 20 more\n\n\n"
			+ "A detailed walkthrough of the error, its code path and all known details is as follows:\n"
			+ "---------------------------------------------------------------------------------------\n\n"
			+ "-- Head --\nThread: Render thread\nStacktrace:\n\tat java.lang.Thread.run(Thread.java:1583)\n";

	private static final String NPE_REPORT = "---- Minecraft Crash Report ----\n"
			+ "Description: Rendering overlay\n\n"
			+ "java.lang.NullPointerException: Cannot invoke \"net.minecraft.class_1799.method_7909()\" because \"stack\" is null\n"
			+ "\tat com.example.coolmod.client.HudRenderer.draw(HudRenderer.java:42) ~[coolmod-1.0.jar:?]\n"
			+ "\tat net.minecraft.class_329.handler$zza000$coolmod$onRender(class_329.java:1000)\n"
			+ "\tat net.minecraft.class_329.method_1753(class_329.java:200)\n"
			+ "\tSuppressed: java.lang.IllegalStateException: should be ignored\n"
			+ "\t\tat net.minecraft.class_1.x(class_1.java:1)\n\n"
			+ "-- Head --\n";

	@Test
	public void mixinCrashPointsToTheMod() {
		CrashAnalysis a = CrashAnalysis.analyze(MIXIN_REPORT, null);
		Assertions.assertEquals(3, a.chain.size());
		Assertions.assertEquals("org.spongepowered.asm.mixin.injection.throwables.InjectionError", a.root.type);
		Assertions.assertEquals(CrashAnalysis.Kind.MIXIN, a.kind);
		Assertions.assertEquals("fancyhud", a.mixinMod);
		Assertions.assertNull(a.firstLogError);
	}

	@Test
	public void npeInModCodeIsAModError() {
		CrashAnalysis a = CrashAnalysis.analyze(NPE_REPORT, null);
		Assertions.assertEquals(1, a.chain.size(), "Suppressed must not become a cause");
		Assertions.assertEquals("java.lang.NullPointerException", a.root.type);
		Assertions.assertEquals("com.example.coolmod.client.HudRenderer.draw", a.root.frames.get(0));
		Assertions.assertEquals(3, a.root.frames.size());
		Assertions.assertEquals(CrashAnalysis.Kind.MOD_ERROR, a.kind);
	}

	@Test
	public void mixinHandlerNamesTheMod() {
		java.util.regex.Matcher m = CrashAnalysis.MIXIN_HANDLER.matcher("net.minecraft.class_329.handler$zza000$coolmod$onRender");
		Assertions.assertTrue(m.find());
		Assertions.assertEquals("coolmod", m.group(1));
	}

	@Test
	public void wrappersAreSkipped() {
		String report = "Description: Ticking\n\n"
				+ "java.util.concurrent.CompletionException: java.lang.NoSuchMethodError: 'void net.minecraft.class_1.foo()'\n"
				+ "\tat java.util.concurrent.CompletableFuture.encodeThrowable(CompletableFuture.java:315)\n"
				+ "Caused by: java.lang.NoSuchMethodError: 'void net.minecraft.class_1.foo()'\n"
				+ "\tat dev.other.mod.Thing.run(Thing.java:10)\n";
		CrashAnalysis a = CrashAnalysis.analyze(report, null);
		Assertions.assertEquals("java.lang.NoSuchMethodError", a.root.type);
		Assertions.assertEquals(CrashAnalysis.Kind.INCOMPATIBLE, a.kind);
	}

	@Test
	public void outOfMemoryWinsOverEverything() {
		String report = "Description: Exception in server tick loop\n\n"
				+ "java.lang.RuntimeException: boom\n\tat dev.mod.A.b(A.java:1)\n"
				+ "Caused by: java.lang.OutOfMemoryError: Java heap space\n\tat java.util.Arrays.copyOf(Arrays.java:1)\n";
		Assertions.assertEquals(CrashAnalysis.Kind.OUT_OF_MEMORY, CrashAnalysis.analyze(report, null).kind);
	}

	@Test
	public void genericVanillaCrashLooksIntoTheLog() {
		String report = "Description: Initializing game\n\n"
				+ "java.lang.IllegalStateException: Registry is already frozen\n"
				+ "\tat net.minecraft.class_2370.method_10272(class_2370.java:300)\n"
				+ "\tat net.minecraft.class_310.<init>(class_310.java:500)\n";
		String log = "[12:00:00] [main/INFO]: Loading Minecraft 1.21.1 with Fabric Loader 0.19.5\n"
				+ "[12:00:01] [main/ERROR]: Realms is not available\n"
				+ "[12:00:02] [Worker-Main-1/ERROR]: Failed to register blocks for mod brokenblocks\n"
				+ "java.lang.IllegalArgumentException: Duplicate key brokenblocks:stone\n"
				+ "[12:00:03] [main/ERROR]: Something else later\n"
				+ "---- Minecraft Crash Report ----\n";
		CrashAnalysis a = CrashAnalysis.analyze(report, log);
		Assertions.assertEquals(CrashAnalysis.Kind.UNKNOWN, a.kind);
		Assertions.assertNotNull(a.firstLogError);
		Assertions.assertTrue(a.firstLogError.contains("brokenblocks"), a.firstLogError);
		Assertions.assertTrue(a.firstLogError.contains("Duplicate key"), a.firstLogError);
	}

	@Test
	public void noStackGivesUnknown() {
		CrashAnalysis a = CrashAnalysis.analyze("garbage without a stack trace", null);
		List<CrashAnalysis.Cause> chain = a.chain;
		Assertions.assertTrue(chain.isEmpty());
		Assertions.assertNull(a.root);
		Assertions.assertEquals(CrashAnalysis.Kind.UNKNOWN, a.kind);
	}
}
