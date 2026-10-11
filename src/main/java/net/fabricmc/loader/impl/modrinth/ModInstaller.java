/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.impl.i18n.Lang;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Качает список версий, разрешённый {@link DependencyResolver}, в папку mods, и ведёт
 * {@link InstalledModsIndex}. Применится только при следующем запуске игры — лоадер уже
 * просканировал mods к моменту, когда пользователь вообще может что-то установить.
 */
public final class ModInstaller {
	private ModInstaller() { }

	public static final class InstallResult {
		public final List<String> installedModNames;
		/** Файлы, положенные в mods, в порядке установки (зависимости раньше самого мода). */
		public final List<Path> installedFiles;
		public final Path modsDir;

		InstallResult(List<String> installedModNames, List<Path> installedFiles, Path modsDir) {
			this.installedModNames = installedModNames;
			this.installedFiles = installedFiles;
			this.modsDir = modsDir;
		}
	}

	/** Ход скачивания: какой по счёту файл из скольки, сколько байт получено (total может быть -1, если сервер не сообщил). */
	public interface Progress {
		void update(int index, int count, String fileName, long done, long total);
	}

	public static InstallResult install(Path modsDir, List<ModrinthVersion> toInstall) throws ModrinthApiException {
		return install(modsDir, toInstall, null);
	}

	public static InstallResult install(Path modsDir, List<ModrinthVersion> toInstall, Progress progress) throws ModrinthApiException {
		return install(modsDir, toInstall, progress, java.util.Collections.emptySet());
	}

	/**
	 * @param avoidNames имена файлов, под которыми ставить нельзя (старая версия того же мода, которую держит игра):
	 *                   новый файл получит другое имя, а старый уйдёт при следующем запуске
	 */
	public static InstallResult install(Path modsDir, List<ModrinthVersion> toInstall, Progress progress, java.util.Set<String> avoidNames) throws ModrinthApiException {
		InstalledModsIndex index = InstalledModsIndex.load(modsDir);
		List<String> installedNames = new ArrayList<>();
		List<Path> installedFiles = new ArrayList<>();

		try {
			Files.createDirectories(modsDir);
		} catch (IOException e) {
			throw new ModrinthApiException(Lang.tr("Не удалось создать папку mods: ") + modsDir, e);
		}

		int fileIndex = 0;

		for (ModrinthVersion version : toInstall) {
			ModrinthFile file = version.getPrimaryFile();

			if (file == null || file.url == null) {
				throw new ModrinthApiException(Lang.tr("У версии \"") + version.name + Lang.tr("\" мода ") + version.projectId + Lang.tr(" на Modrinth нет скачиваемого файла"));
			}

			String targetName = file.filename;

			if (avoidNames.contains(targetName)) {
				int dot = targetName.lastIndexOf('.');
				targetName = (dot > 0 ? targetName.substring(0, dot) : targetName) + "+argentum" + (System.currentTimeMillis() % 100000) + (dot > 0 ? targetName.substring(dot) : "");
			}

			Path target = modsDir.resolve(targetName);

			Log.info(LogCategory.MODRINTH, "Скачиваю %s (%s)...", file.filename, version.name);
			final int current = fileIndex++;
			final int count = toInstall.size();
			downloadWithRetries(file, target, progress == null ? null : (done, total) -> progress.update(current, count, file.filename, done, total));

			index.put(version.projectId, version.id, targetName);
			installedNames.add(version.name != null ? version.name : file.filename);
			installedFiles.add(target);
		}

		try {
			index.save();
		} catch (IOException e) {
			throw new ModrinthApiException(Lang.tr("Моды скачаны, но не удалось сохранить реестр установленных модов"), e);
		}

		return new InstallResult(installedNames, installedFiles, modsDir);
	}

	/** Канал до CDN бывает медленным и рвётся — пробуем несколько раз, а не сдаёмся на первой ошибке. */
	private static void downloadWithRetries(ModrinthFile file, Path target, ByteProgress progress) throws ModrinthApiException {
		final int attempts = 3;

		for (int attempt = 1; ; attempt++) {
			try {
				downloadAndVerify(file, target, progress);
				return;
			} catch (ModrinthApiException e) {
				Log.warn(LogCategory.MODRINTH, "Попытка %d/%d скачать %s не удалась: %s", attempt, attempts, file.filename, describe(e));

				if (attempt >= attempts) throw e;
			}
		}
	}

	private interface ByteProgress {
		void update(long done, long total);
	}

	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder();

		for (Throwable cur = t; cur != null; cur = cur.getCause()) {
			if (sb.length() > 0) sb.append(" <- ");
			sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
		}

		return sb.toString();
	}

	private static void downloadAndVerify(ModrinthFile file, Path target, ByteProgress progress) throws ModrinthApiException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");

		try {
			HttpURLConnection connection = ModrinthNet.open(file.url, 30_000, 60_000);

			int status = connection.getResponseCode();

			if (status != 200) {
				throw new ModrinthApiException(Lang.tr("Не удалось скачать ") + file.filename + Lang.tr(" — сервер ответил HTTP ") + status);
			}

			MessageDigest sha1Digest = file.sha1 != null ? newSha1() : null;
			long total = connection.getContentLengthLong();
			long done = 0;

			try (InputStream is = connection.getInputStream();
					OutputStream os = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[8192];
				int read;

				while ((read = is.read(buf)) >= 0) {
					os.write(buf, 0, read);
					if (sha1Digest != null) sha1Digest.update(buf, 0, read);
					done += read;

					if (progress != null) {
						progress.update(done, total);
					}
				}
			}

			if (sha1Digest != null) {
				String actual = toHex(sha1Digest.digest());

				if (!actual.equalsIgnoreCase(file.sha1)) {
					Files.deleteIfExists(tmp);
					throw new ModrinthApiException(Lang.tr("Скачанный файл ") + file.filename + Lang.tr(" повреждён: контрольная сумма не совпадает ")
							+ Lang.tr("(ожидалось ") + file.sha1 + Lang.tr(", получено ") + actual + ")");
				}
			}

			Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// неважно, это временный файл
			}

			throw new ModrinthApiException(Lang.tr("Не удалось скачать ") + file.filename, e);
		}
	}

	private static MessageDigest newSha1() throws ModrinthApiException {
		try {
			return MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new ModrinthApiException(Lang.tr("SHA-1 недоступен в этой JVM"), e);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);

		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}

		return sb.toString();
	}
}
