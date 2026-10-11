/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE-ARGENTUM.txt.
 */

package com.ganichgg.argentum.client.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Файл настроек мода как плоский список записей «ключ — значение» для встроенного редактора.
 * Поддерживаются JSON/JSON5, TOML и .properties. Перед первой записью рядом кладётся копия {@code <имя>.argentum.bak}.
 */
public abstract class ConfigDoc {
	public enum Kind { BOOL, INT, FLOAT, STRING, RAW }

	/** Одна настройка. value — текст, как его видит и правит человек; original — как было при загрузке. */
	public static final class Entry {
		public final String label; // последнее имя ключа
		public final String group; // путь до него (таблица/объект), может быть пустым
		public final Kind kind;
		public String value;
		public final String original;
		final List<String> keys; // для JSON: путь по дереву
		int line = -1; // для построчных форматов
		int from;
		int to;
		boolean quoted;

		Entry(String label, String group, Kind kind, String value, List<String> keys) {
			this.label = label;
			this.group = group;
			this.kind = kind;
			this.value = value;
			this.original = value;
			this.keys = keys;
		}

		public boolean dirty() {
			return !this.value.equals(this.original);
		}

		/** Подходит ли текст под тип настройки. */
		public boolean valid(String text) {
			try {
				switch (this.kind) {
				case BOOL:
					return text.equals("true") || text.equals("false");
				case INT:
					Long.parseLong(text.trim());
					return true;
				case FLOAT:
					Double.parseDouble(text.trim());
					return true;
				case RAW:
					if (this.keys != null) { // JSON: сырое значение должно разбираться
						MiniJson.parse(text, null);
					}

					return !text.trim().isEmpty();
				default:
					return true;
				}
			} catch (IOException | NumberFormatException e) {
				return false;
			}
		}
	}

	protected final Path file;
	protected final List<Entry> entries = new ArrayList<>();

	ConfigDoc(Path file) {
		this.file = file;
	}

	public Path file() {
		return this.file;
	}

	public List<Entry> entries() {
		return this.entries;
	}

	public boolean dirty() {
		for (Entry e : this.entries) {
			if (e.dirty()) {
				return true;
			}
		}

		return false;
	}

	/** При сохранении пропадут комментарии (JSON5 с комментариями). */
	public boolean lossy() {
		return false;
	}

	public abstract void save() throws IOException;

	public static boolean supports(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);

		return n.endsWith(".json") || n.endsWith(".json5") || n.endsWith(".toml") || n.endsWith(".properties");
	}

	public static ConfigDoc load(Path p) throws IOException {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);

		if (n.endsWith(".json") || n.endsWith(".json5")) {
			return new JsonDoc(p);
		}

		return new LineDoc(p, n.endsWith(".toml"));
	}

	protected void backupOnce() throws IOException {
		Path bak = this.file.resolveSibling(this.file.getFileName() + ".argentum.bak");

		if (!Files.exists(bak)) {
			Files.copy(this.file, bak);
		}
	}

	// ---------------------------------------------------------------- JSON

	static final class JsonDoc extends ConfigDoc {
		private final Object root;
		private final boolean lossy;

		JsonDoc(Path file) throws IOException {
			super(file);
			String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			boolean[] comments = new boolean[1];
			this.root = MiniJson.parse(text, comments);
			this.lossy = comments[0] || file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json5");

			if (!(this.root instanceof Map)) {
				throw new IOException("корень файла — не объект");
			}

			flatten(this.root, new ArrayList<>());
		}

		@Override
		public boolean lossy() {
			return this.lossy;
		}

		@SuppressWarnings("unchecked")
		private void flatten(Object node, List<String> path) {
			for (Map.Entry<String, Object> e : ((Map<String, Object>) node).entrySet()) {
				List<String> keys = new ArrayList<>(path);
				keys.add(e.getKey());
				Object v = e.getValue();

				if (v instanceof Map && !((Map<String, Object>) v).isEmpty()) {
					flatten(v, keys);
					continue;
				}

				String group = String.join(" › ", path);
				Kind kind;
				String text;

				if (v instanceof Boolean) {
					kind = Kind.BOOL;
					text = v.toString();
				} else if (v instanceof Long) {
					kind = Kind.INT;
					text = v.toString();
				} else if (v instanceof Double) {
					kind = Kind.FLOAT;
					text = MiniJson.inline(v);
				} else if (v instanceof String) {
					kind = Kind.STRING;
					text = (String) v;
				} else {
					kind = Kind.RAW; // массивы, пустые объекты, null
					text = MiniJson.inline(v);
				}

				this.entries.add(new Entry(e.getKey(), group, kind, text, keys));
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		public void save() throws IOException {
			backupOnce();

			for (Entry e : this.entries) {
				if (!e.dirty()) {
					continue;
				}

				Object node = this.root;

				for (int i = 0; i < e.keys.size() - 1; i++) {
					node = ((Map<String, Object>) node).get(e.keys.get(i));
				}

				Object value;

				switch (e.kind) {
				case BOOL:
					value = Boolean.valueOf(e.value);
					break;
				case INT:
					value = Long.valueOf(e.value.trim());
					break;
				case FLOAT:
					value = Double.valueOf(e.value.trim());
					break;
				case RAW:
					value = MiniJson.parse(e.value, null);
					break;
				default:
					value = e.value;
				}

				((Map<String, Object>) node).put(e.keys.get(e.keys.size() - 1), value);
			}

			Files.write(this.file, MiniJson.write(this.root).getBytes(StandardCharsets.UTF_8));
		}
	}

	// ---------------------------------------------------------------- TOML и .properties: правка прямо в строках

	static final class LineDoc extends ConfigDoc {
		private static final Pattern INT = Pattern.compile("[+-]?\\d[\\d_]*");
		private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d[\\d_]*)?\\.\\d[\\d_]*([eE][+-]?\\d+)?|[+-]?\\d[\\d_]*[eE][+-]?\\d+");

		private final List<String> lines = new ArrayList<>();
		private final boolean toml;

		LineDoc(Path file, boolean toml) throws IOException {
			super(file);
			this.toml = toml;
			String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

			for (String l : text.split("\r?\n", -1)) {
				this.lines.add(l);
			}

			parse();
		}

		private void parse() {
			String table = "";
			boolean skipTable = false;

			for (int i = 0; i < this.lines.size(); i++) {
				String line = this.lines.get(i);
				String t = line.trim();

				if (t.isEmpty() || t.startsWith("#") || t.startsWith("!") || t.startsWith(";")) {
					continue;
				}

				if (this.toml && t.startsWith("[[")) {
					skipTable = true; // массивы таблиц не правим: слишком легко испортить
					continue;
				}

				if (this.toml && t.startsWith("[")) {
					int close = t.indexOf(']');
					table = close > 0 ? t.substring(1, close).trim().replace("\"", "") : t;
					skipTable = false;
					continue;
				}

				if (skipTable) {
					continue;
				}

				int sep = findSeparator(line);

				if (sep < 0) {
					continue;
				}

				String key = line.substring(0, sep).trim().replace("\"", "");
				int valueStart = sep + 1;

				while (valueStart < line.length() && line.charAt(valueStart) == ' ') {
					valueStart++;
				}

				int valueEnd = this.toml ? tomlValueEnd(line, valueStart) : line.length();

				if (valueEnd < 0) {
					continue; // многострочное или непонятное значение — пропускаем
				}

				String raw = line.substring(valueStart, valueEnd).trim();
				valueEnd = valueStart + line.substring(valueStart, valueEnd).stripTrailing().length();
				Kind kind;
				String shown = raw;
				boolean quoted = false;

				if (raw.equals("true") || raw.equals("false")) {
					kind = Kind.BOOL;
				} else if (INT.matcher(raw).matches()) {
					kind = Kind.INT;
					shown = raw.replace("_", "");
				} else if (FLOAT.matcher(raw).matches()) {
					kind = Kind.FLOAT;
					shown = raw.replace("_", "");
				} else if (this.toml && raw.length() >= 2 && (raw.startsWith("\"") && raw.endsWith("\"") || raw.startsWith("'") && raw.endsWith("'"))) {
					kind = Kind.STRING;
					shown = raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
					quoted = true;
				} else if (this.toml) {
					kind = Kind.RAW;
				} else {
					kind = Kind.STRING;
				}

				Entry e = new Entry(key, table, kind, shown, null);
				e.line = i;
				e.from = valueStart;
				e.to = valueEnd;
				e.quoted = quoted;
				this.entries.add(e);
			}
		}

		private int findSeparator(String line) {
			boolean inQuote = false;

			for (int i = 0; i < line.length(); i++) {
				char c = line.charAt(i);

				if (c == '"') {
					inQuote = !inQuote;
				} else if (!inQuote && (c == '=' || (!this.toml && c == ':'))) {
					return i;
				}
			}

			return -1;
		}

		/** Конец значения в TOML-строке (до комментария); -1, если массив/таблица не закрыты на этой же строке. */
		private int tomlValueEnd(String line, int start) {
			char quote = 0;
			int depth = 0;

			for (int i = start; i < line.length(); i++) {
				char c = line.charAt(i);

				if (quote != 0) {
					if (c == '\\' && quote == '"') {
						i++;
					} else if (c == quote) {
						quote = 0;
					}
				} else if (c == '"' || c == '\'') {
					quote = c;
				} else if (c == '[' || c == '{') {
					depth++;
				} else if (c == ']' || c == '}') {
					depth--;
				} else if (c == '#') {
					return depth == 0 ? i : -1;
				}
			}

			return depth == 0 && quote == 0 ? line.length() : -1;
		}

		@Override
		public void save() throws IOException {
			backupOnce();

			for (Entry e : this.entries) {
				if (!e.dirty()) {
					continue;
				}

				String line = this.lines.get(e.line);
				String text = e.value;

				if (this.toml && e.kind == Kind.STRING) {
					text = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
				} else if (!this.toml) {
					text = text.replace("\n", "\\n");
				}

				this.lines.set(e.line, line.substring(0, e.from) + text + line.substring(e.to));
			}

			Files.write(this.file, String.join(System.lineSeparator(), this.lines).getBytes(StandardCharsets.UTF_8));
		}
	}
}
