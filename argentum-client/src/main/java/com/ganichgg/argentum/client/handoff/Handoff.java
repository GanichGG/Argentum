package com.ganichgg.argentum.client.handoff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.minecraft.class_1041;
import net.minecraft.class_310;

/**
 * Бесшовное применение модов: пока пользователь сидит в каталоге, в фоне поднимается вторая копия игры — скрытая, уже
 * с новыми модами. Когда она готова, «Применить» подменяет окно: новое появляется ровно на месте старого, старое
 * закрывается. Если мод ломает запуск, умирает только фоновая копия — текущая игра остаётся целой.
 *
 * <p>Обмен между процессами — файлы в {@code .argentum/handoff/<id>/}: {@code ready} (копия дошла до главного меню),
 * {@code go} (параметры окна, команда показаться), {@code shown} (копия показана, можно закрываться), {@code abort}.
 *
 * <p>class_310 = MinecraftClient, class_1041 = Window (method_4490 = getHandle, method_4498 = isFullscreen,
 * method_4500 = toggleFullscreen), method_1592 = scheduleStop.
 */
public final class Handoff {
	public enum State { IDLE, PREPARING, READY, SWAPPING, FAILED }

	private static final String PROP_DIR = "argentum.handoff";
	private static final String PROP_PARENT = "argentum.parentPid";
	private static final long DEBOUNCE_MS = 1500;
	private static final long PREPARE_TIMEOUT_MS = 6 * 60 * 1000;

	/** Эта копия игры запущена как фоновая (скрытая), пока её не показали. */
	private static final String CHILD_DIR = System.getProperty(PROP_DIR);
	private static volatile boolean adopted;

	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "argentum-handoff");
		t.setDaemon(true);
		return t;
	});

	private static final Object LOCK = new Object();
	private static volatile State state = State.IDLE;
	private static volatile String failure = "";
	private static volatile long startedAt;
	private static Process child;
	private static Path controlDir;
	private static int generation;
	private static boolean hookInstalled;
	private static boolean childReadyWritten;

	private Handoff() { }

	// ---------------------------------------------------------------- общее

	/** Это скрытая фоновая копия, которую ещё не показали пользователю. */
	public static boolean isHiddenChild() {
		return CHILD_DIR != null && !adopted;
	}

	public static State state() {
		return state;
	}

	public static String failure() {
		return failure;
	}

	public static long elapsedMs() {
		return state == State.PREPARING ? System.currentTimeMillis() - startedAt : 0;
	}

	// ---------------------------------------------------------------- сторона основной игры

	/**
	 * Моды изменились так, что нужен новый запуск: готовим фоновую копию. Повторные вызовы подряд склеиваются, а если
	 * копия уже готовится — она перезапускается, чтобы учесть последнее изменение.
	 */
	public static void requestPrepare() {
		if (isHiddenChild()) {
			return;
		}

		int mine;

		synchronized (LOCK) {
			mine = ++generation;

			if (state == State.SWAPPING) {
				return;
			}

			state = State.PREPARING;
			startedAt = System.currentTimeMillis();
			failure = "";
		}

		TIMER.schedule(() -> startChild(mine), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	private static void startChild(int mine) {
		Process started;
		Path dir;

		synchronized (LOCK) {
			if (mine != generation || state == State.SWAPPING) {
				return; // за это время изменения были ещё раз — запуск будет по последнему
			}

			killChildLocked();

			try {
				Path root = FabricLoader.getInstance().getGameDir().resolve(".argentum").resolve("handoff");
				cleanOldDirs(root);
				dir = root.resolve(Long.toString(System.currentTimeMillis()));
				Files.createDirectories(dir);
				List<String> command = buildCommand(dir);
				ProcessBuilder pb = new ProcessBuilder(command);
				pb.directory(FabricLoader.getInstance().getGameDir().toFile());
				pb.redirectErrorStream(true);
				pb.redirectOutput(dir.resolve("child.log").toFile());
				started = pb.start();
			} catch (Exception e) {
				Log.warn(LogCategory.GENERAL, "Не удалось запустить фоновую копию игры", e);
				state = State.FAILED;
				failure = "не удалось запустить: " + e.getMessage();
				return;
			}

			child = started;
			controlDir = dir;
			startedAt = System.currentTimeMillis();
			installHook();
		}

		Log.info(LogCategory.GENERAL, "Handoff: фоновая копия игры запущена (pid %d)", started.pid());
		watch(started, dir, mine);
	}

	private static void watch(Process process, Path dir, int mine) {
		Thread t = new Thread(() -> {
			long deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS;

			while (true) {
				synchronized (LOCK) {
					if (mine != generation || child != process || state == State.SWAPPING) {
						return;
					}
				}

				if (Files.exists(dir.resolve("ready"))) {
					synchronized (LOCK) {
						if (mine == generation && child == process && state == State.PREPARING) {
							state = State.READY;
						}
					}

					Log.info(LogCategory.GENERAL, "Handoff: фоновая копия готова за %d с", (System.currentTimeMillis() - startedAt) / 1000);
					// дальше следим, чтобы она не умерла
					while (process.isAlive()) {
						sleep(500);

						synchronized (LOCK) {
							if (mine != generation || child != process || state != State.READY) {
								return;
							}
						}
					}

					fail(process, "фоновая копия неожиданно закрылась");
					return;
				}

				if (!process.isAlive()) {
					fail(process, "фоновая копия не запустилась (код " + process.exitValue() + ")");
					return;
				}

				if (System.currentTimeMillis() > deadline) {
					fail(process, "фоновая копия слишком долго запускается");
					process.destroyForcibly();
					return;
				}

				sleep(300);
			}
		}, "argentum-handoff-watch");
		t.setDaemon(true);
		t.start();
	}

	private static void fail(Process process, String message) {
		synchronized (LOCK) {
			if (child != process || state == State.SWAPPING) {
				return;
			}

			state = State.FAILED;
			failure = message;
		}

		Log.warn(LogCategory.GENERAL, "Handoff: " + message + " (подробности: child.log в " + controlDir + ")");
	}

	/**
	 * «Применить»: окно новой копии появляется на месте старого. Вызывать из потока рендера (там читаем геометрию окна
	 * и потом закрываем игру).
	 */
	public static boolean swap(class_310 client, boolean openCatalog, String message) {
		Path dir;
		Process process;

		synchronized (LOCK) {
			if (state != State.READY || child == null || !child.isAlive()) {
				return false;
			}

			state = State.SWAPPING;
			dir = controlDir;
			process = child;
		}

		class_1041 window = client.method_22683();
		long handle = window.method_4490();
		Properties go = new Properties();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			java.nio.IntBuffer x = stack.mallocInt(1);
			java.nio.IntBuffer y = stack.mallocInt(1);
			java.nio.IntBuffer w = stack.mallocInt(1);
			java.nio.IntBuffer h = stack.mallocInt(1);
			GLFW.glfwGetWindowPos(handle, x, y);
			GLFW.glfwGetWindowSize(handle, w, h);
			go.setProperty("x", Integer.toString(x.get(0)));
			go.setProperty("y", Integer.toString(y.get(0)));
			go.setProperty("w", Integer.toString(w.get(0)));
			go.setProperty("h", Integer.toString(h.get(0)));
		}

		go.setProperty("maximized", Boolean.toString(GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE));
		go.setProperty("fullscreen", Boolean.toString(window.method_4498()));
		go.setProperty("openCatalog", Boolean.toString(openCatalog));
		go.setProperty("message", message == null ? "" : message);

		try {
			writeProps(dir.resolve("go.tmp"), go);
			Files.move(dir.resolve("go.tmp"), dir.resolve("go"));
		} catch (IOException e) {
			synchronized (LOCK) {
				state = State.FAILED;
				failure = "не удалось передать команду: " + e.getMessage();
			}

			return false;
		}

		Thread t = new Thread(() -> awaitShown(client, handle, process, dir), "argentum-handoff-swap");
		t.setDaemon(true);
		t.start();

		return true;
	}

	private static void awaitShown(class_310 client, long handle, Process process, Path dir) {
		long deadline = System.currentTimeMillis() + 20_000;

		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(dir.resolve("shown"))) {
				// новое окно уже на экране — скрываем своё и закрываемся
				client.execute(() -> {
					GLFW.glfwHideWindow(handle);
					client.method_1592();
				});

				sleep(6000);
				Runtime.getRuntime().halt(0); // на случай, если штатное закрытие зависнет
				return;
			}

			if (!process.isAlive()) {
				break;
			}

			sleep(100);
		}

		try {
			Files.createFile(dir.resolve("abort"));
		} catch (IOException ignored) {
			// неважно
		}

		synchronized (LOCK) {
			state = State.FAILED;
			failure = "новая копия не отозвалась, остаёмся в текущей";
		}
	}

	private static void killChildLocked() {
		if (child != null && child.isAlive()) {
			child.destroyForcibly();
		}

		child = null;
	}

	private static void installHook() {
		if (hookInstalled) {
			return;
		}

		hookInstalled = true;

		// закрыли игру, не применяя — фоновую копию не оставляем висеть скрытой
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			synchronized (LOCK) {
				if (state != State.SWAPPING) {
					killChildLocked();
				}
			}
		}, "argentum-handoff-cleanup"));
	}

	private static void cleanOldDirs(Path root) {
		if (!Files.isDirectory(root)) {
			return;
		}

		try (Stream<Path> dirs = Files.list(root)) {
			for (Path p : (Iterable<Path>) dirs::iterator) {
				try (Stream<Path> files = Files.walk(p)) {
					files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
				} catch (IOException ignored) {
					// старый каталог чужого (возможно, ещё живого) процесса — оставляем
				}
			}
		} catch (IOException ignored) {
			// не критично
		}
	}

	/** Та же JVM с теми же аргументами, что и у нас, плюс признак фоновой копии. */
	private static List<String> buildCommand(Path dir) {
		List<String> cmd = new ArrayList<>();
		cmd.add(ProcessHandle.current().info().command()
				.orElseGet(() -> Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString()));

		for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (!arg.startsWith("-D" + PROP_DIR) && !arg.startsWith("-D" + PROP_PARENT)) {
				cmd.add(arg);
			}
		}

		cmd.add("-D" + PROP_DIR + "=" + dir);
		cmd.add("-D" + PROP_PARENT + "=" + ProcessHandle.current().pid());
		cmd.add("-cp");
		cmd.add(System.getProperty("java.class.path"));

		// Prism запускает игру через EntryPoint, который берёт параметры из stdin — повторить это нельзя. Но игре
		// всё равно, кто вызвал KnotClient.main: аргументы запуска лоадер помнит сам.
		cmd.add("net.fabricmc.loader.impl.launch.knot.KnotClient");
		cmd.addAll(java.util.Arrays.asList(FabricLoader.getInstance().getLaunchArguments(false)));

		return cmd;
	}

	// ---------------------------------------------------------------- сторона фоновой копии

	/** Из экрана главного меню: копия дошла до конца загрузки — сообщаем и прячемся до команды. */
	public static void childReady(class_310 client) {
		if (!isHiddenChild() || childReadyWritten) {
			return;
		}

		childReadyWritten = true;
		GLFW.glfwHideWindow(client.method_22683().method_4490()); // на случай, если окно успело показаться

		Path dir = Paths.get(CHILD_DIR);

		try {
			Files.createFile(dir.resolve("ready"));
		} catch (IOException e) {
			Log.warn(LogCategory.GENERAL, "Handoff: не удалось сообщить о готовности", e);
		}

		long parent = Long.parseLong(System.getProperty(PROP_PARENT, "0"));

		// не оставляем скрытое окно, если основная игра закрылась, так и не применив
		Thread watcher = new Thread(() -> {
			while (!adopted) {
				boolean parentAlive = ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false);

				if ((!parentAlive && !Files.exists(dir.resolve("go"))) || Files.exists(dir.resolve("abort"))) {
					Runtime.getRuntime().halt(0);
				}

				sleep(1000);
			}
		}, "argentum-handoff-parent-watch");
		watcher.setDaemon(true);
		watcher.start();
	}

	/**
	 * Каждый кадр главного меню в скрытой копии: ждём команду «показаться». Заодно не даём скрытому окну крутиться на
	 * полной скорости (без вертикальной синхронизации оно бы грузило процессор впустую).
	 *
	 * @return параметры показа, если копия только что показана; иначе null
	 */
	public static Properties childPoll(class_310 client) {
		if (!isHiddenChild() || !childReadyWritten) {
			return null;
		}

		Path dir = Paths.get(CHILD_DIR);
		Path goFile = dir.resolve("go");

		if (!Files.exists(goFile)) {
			sleep(20);
			return null;
		}

		Properties go = new Properties();

		try (InputStream in = Files.newInputStream(goFile)) {
			go.load(in);
		} catch (IOException e) {
			return null;
		}

		class_1041 window = client.method_22683();
		long handle = window.method_4490();

		if (Boolean.parseBoolean(go.getProperty("fullscreen"))) {
			window.method_4500(); // применится в ближайшем кадре
		} else {
			GLFW.glfwSetWindowPos(handle, Integer.parseInt(go.getProperty("x", "100")), Integer.parseInt(go.getProperty("y", "100")));
			GLFW.glfwSetWindowSize(handle, Integer.parseInt(go.getProperty("w", "854")), Integer.parseInt(go.getProperty("h", "480")));

			if (Boolean.parseBoolean(go.getProperty("maximized"))) {
				GLFW.glfwMaximizeWindow(handle);
			}
		}

		GLFW.glfwShowWindow(handle);
		GLFW.glfwFocusWindow(handle);
		adopted = true; // с этого момента это основная игра: звук включается

		try {
			Files.createFile(dir.resolve("shown"));
		} catch (IOException ignored) {
			// неважно
		}

		return go;
	}

	// ---------------------------------------------------------------- утилиты

	private static void writeProps(Path file, Properties p) throws IOException {
		try (OutputStream out = Files.newOutputStream(file)) {
			p.store(out, null);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
