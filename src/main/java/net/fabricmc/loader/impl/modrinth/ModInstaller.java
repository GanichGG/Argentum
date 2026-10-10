/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

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
		InstalledModsIndex index = InstalledModsIndex.load(modsDir);
		List<String> installedNames = new ArrayList<>();
		List<Path> installedFiles = new ArrayList<>();

		try {
			Files.createDirectories(modsDir);
		} catch (IOException e) {
			throw new ModrinthApiException("Не удалось создать папку mods: " + modsDir, e);
		}

		int fileIndex = 0;

		for (ModrinthVersion version : toInstall) {
			ModrinthFile file = version.getPrimaryFile();

			if (file == null || file.url == null) {
				throw new ModrinthApiException("У версии \"" + version.name + "\" мода " + version.projectId + " на Modrinth нет скачиваемого файла");
			}

			Path target = modsDir.resolve(file.filename);

			Log.info(LogCategory.MODRINTH, "Скачиваю %s (%s)...", file.filename, version.name);
			final int current = fileIndex++;
			final int count = toInstall.size();
			downloadWithRetries(file, target, progress == null ? null : (done, total) -> progress.update(current, count, file.filename, done, total));

			index.put(version.projectId, version.id, file.filename);
			installedNames.add(version.name != null ? version.name : file.filename);
			installedFiles.add(target);
		}

		try {
			index.save();
		} catch (IOException e) {
			throw new ModrinthApiException("Моды скачаны, но не удалось сохранить реестр установленных модов", e);
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
			HttpURLConnection connection = (HttpURLConnection) new URL(file.url).openConnection();
			connection.setConnectTimeout(30_000);
			connection.setReadTimeout(60_000);

			int status = connection.getResponseCode();

			if (status != 200) {
				throw new ModrinthApiException("Не удалось скачать " + file.filename + " — сервер ответил HTTP " + status);
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
					throw new ModrinthApiException("Скачанный файл " + file.filename + " повреждён: контрольная сумма не совпадает "
							+ "(ожидалось " + file.sha1 + ", получено " + actual + ")");
				}
			}

			Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// неважно, это временный файл
			}

			throw new ModrinthApiException("Не удалось скачать " + file.filename, e);
		}
	}

	private static MessageDigest newSha1() throws ModrinthApiException {
		try {
			return MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new ModrinthApiException("SHA-1 недоступен в этой JVM", e);
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
