/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.hotload;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import com.ganichgg.argentum.core.i18n.Lang;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * Решает, можно ли мод безопасно подключить к уже запущенной игре. Всё, что требует раннего старта (патчи уже
 * загруженных классов, access widener, регистрация блоков/предметов), отправляется на перезапуск — с понятной причиной.
 */
final class HotLoadAnalyzer {
	private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String REGISTRY_CLASS = "net/minecraft/class_2378"; // net.minecraft.registry.Registry (intermediary)
	private static final String REGISTRY_ARG = "(L" + REGISTRY_CLASS + ";";
	private static final int MAX_NAMED = 3;

	private HotLoadAnalyzer() { }

	/** Причины, по которым мод нельзя загрузить на лету (пусто — можно). */
	static List<String> blockers(ModCandidateImpl candidate, EnvType env) {
		List<String> reasons = new ArrayList<>();
		LoaderModMetadata md = candidate.getMetadata();

		if (md.getClassTweaker() != null) {
			reasons.add(Lang.tr("использует access widener — он применяется только при старте игры"));
		}

		if (!md.getLanguageAdapterDefinitions().isEmpty()) {
			reasons.add(Lang.tr("приносит свой языковой адаптер"));
		}

		if (md.getEntrypointKeys().contains("preLaunch")) {
			reasons.add(Lang.tr("должен запускаться до игры (preLaunch)"));
		}

		Path jar = candidate.getPaths().get(0);

		if (!Files.isRegularFile(jar)) {
			return reasons;
		}

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Set<String> mixinTargets = new LinkedHashSet<>();

			for (String config : md.getMixinConfigs(env)) {
				collectMixinTargets(zip, config, mixinTargets);
			}

			List<String> loaded = new ArrayList<>();

			for (String target : mixinTargets) {
				if (FabricLauncherBase.getLauncher().isClassLoaded(target)) {
					loaded.add(target);
				}
			}

			if (!loaded.isEmpty()) {
				reasons.add(Lang.tr("правит классы игры, которые уже загружены: ") + preview(loaded));
			}

			if (registersContent(zip)) {
				reasons.add(Lang.tr("добавляет блоки, предметы или другое содержимое — реестры игры уже закрыты"));
			}
		} catch (IOException | RuntimeException e) {
			reasons.add(Lang.tr("не удалось проанализировать файл: ") + e.getMessage());
		}

		return reasons;
	}

	/** У мода есть своё содержимое assets/ — ресурсы нужно перезагрузить, чтобы игра их увидела. */
	static boolean hasAssets(ModCandidateImpl candidate) {
		Path jar = candidate.getPaths().get(0);

		if (!Files.isRegularFile(jar)) {
			return false;
		}

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();

				if (name.startsWith("assets/") || name.startsWith("data/")) {
					return true;
				}
			}
		} catch (IOException e) {
			return false;
		}

		return false;
	}

	private static String preview(List<String> names) {
		List<String> shown = new ArrayList<>();

		for (int i = 0; i < Math.min(MAX_NAMED, names.size()); i++) {
			String n = names.get(i);
			shown.add(n.substring(n.lastIndexOf('.') + 1));
		}

		return String.join(", ", shown) + (names.size() > MAX_NAMED ? Lang.tr(" и ещё ") + (names.size() - MAX_NAMED) : "");
	}

	// ---------------------------------------------------------------- миксины

	private static void collectMixinTargets(ZipFile zip, String config, Set<String> out) throws IOException {
		ZipEntry entry = zip.getEntry(config);

		if (entry == null) {
			return;
		}

		String pkg = "";
		List<String> classes = new ArrayList<>();

		try (JsonReader reader = new JsonReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
			reader.setLenient(true);
			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();

				if (key.equals("package") && reader.peek() == JsonToken.STRING) {
					pkg = reader.nextString();
				} else if (key.equals("mixins") || key.equals("client")) {
					reader.beginArray();

					while (reader.hasNext()) {
						classes.add(reader.nextString());
					}

					reader.endArray();
				} else {
					reader.skipValue();
				}
			}
		}

		for (String cls : classes) {
			String path = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + cls.replace('.', '/') + ".class";
			ZipEntry classEntry = zip.getEntry(path);

			if (classEntry == null) {
				continue;
			}

			try (InputStream in = zip.getInputStream(classEntry)) {
				new ClassReader(in).accept(new MixinTargetVisitor(out), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		}
	}

	/** Достаёт цели из @Mixin(value = ..., targets = ...). */
	private static final class MixinTargetVisitor extends ClassVisitor {
		private final Set<String> out;

		MixinTargetVisitor(Set<String> out) {
			super(Opcodes.ASM9);
			this.out = out;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (!MIXIN_ANNOTATION.equals(descriptor)) {
				return null;
			}

			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitArray(String name) {
					return new AnnotationVisitor(Opcodes.ASM9) {
						@Override
						public void visit(String ignored, Object value) {
							if (value instanceof Type) {
								out.add(((Type) value).getClassName());
							} else if (value instanceof String) {
								out.add(((String) value).replace('/', '.'));
							}
						}
					};
				}
			};
		}
	}

	// ---------------------------------------------------------------- регистрация содержимого

	/** Есть ли в коде вызовы Registry.register(...): так мод добавляет блоки, предметы, энтити и прочее. */
	private static boolean registersContent(ZipFile zip) throws IOException {
		boolean[] found = {false};
		Enumeration<? extends ZipEntry> entries = zip.entries();

		while (entries.hasMoreElements() && !found[0]) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();

			if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
				continue;
			}

			try (InputStream in = zip.getInputStream(entry)) {
				new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
					@Override
					public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
						return new MethodVisitor(Opcodes.ASM9) {
							@Override
							public void visitMethodInsn(int opcode, String owner, String insnName, String insnDescriptor, boolean isInterface) {
								if (opcode == Opcodes.INVOKESTATIC && owner.equals(REGISTRY_CLASS) && insnDescriptor.startsWith(REGISTRY_ARG)) {
									found[0] = true;
								}
							}
						};
					}
				}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			} catch (RuntimeException e) {
				// нестандартный class-файл (например, более новая версия формата) — просто пропускаем
				continue;
			}
		}

		return found[0];
	}
}
