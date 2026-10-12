/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package com.ganichgg.argentum.client.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.impl.i18n.Lang;

/**
 * Крошечный разборщик JSON/JSON5 для редактора настроек: комментарии, одинарные кавычки, ключи без кавычек и запятые
 * в конце не мешают. Числа — {@link Long} или {@link Double}, объекты — {@link LinkedHashMap} (порядок сохраняется).
 */
final class MiniJson {
	private final String s;
	private int pos;
	boolean sawComment;

	private MiniJson(String s) {
		this.s = s;
	}

	/** Разбор документа; sawComment у результата (через массив) — были ли комментарии, которые при записи пропадут. */
	static Object parse(String text, boolean[] sawComment) throws IOException {
		MiniJson p = new MiniJson(text);
		p.skip();
		Object value = p.value();
		p.skip();

		if (p.pos < text.length()) {
			throw new IOException(Lang.tr("лишние данные после JSON в позиции ") + p.pos);
		}

		if (sawComment != null) {
			sawComment[0] = p.sawComment;
		}

		return value;
	}

	private void skip() throws IOException {
		while (pos < s.length()) {
			char c = s.charAt(pos);

			if (Character.isWhitespace(c) || c == '﻿') {
				pos++;
			} else if (c == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '/') {
				sawComment = true;

				while (pos < s.length() && s.charAt(pos) != '\n') {
					pos++;
				}
			} else if (c == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '*') {
				sawComment = true;
				int end = s.indexOf("*/", pos + 2);

				if (end < 0) {
					throw new IOException(Lang.tr("не закрыт комментарий"));
				}

				pos = end + 2;
			} else if (c == '#') {
				sawComment = true;

				while (pos < s.length() && s.charAt(pos) != '\n') {
					pos++;
				}
			} else {
				break;
			}
		}
	}

	private Object value() throws IOException {
		if (pos >= s.length()) {
			throw new IOException(Lang.tr("неожиданный конец JSON"));
		}

		char c = s.charAt(pos);

		if (c == '{') {
			return object();
		}

		if (c == '[') {
			return array();
		}

		if (c == '"' || c == '\'') {
			return string();
		}

		int start = pos;

		while (pos < s.length() && "{}[],:".indexOf(s.charAt(pos)) < 0 && !Character.isWhitespace(s.charAt(pos))) {
			pos++;
		}

		String token = s.substring(start, pos);

		switch (token) {
		case "true":
			return Boolean.TRUE;
		case "false":
			return Boolean.FALSE;
		case "null":
			return null;
		default:
			break;
		}

		try {
			if (token.matches("[+-]?\\d+")) {
				return Long.parseLong(token.startsWith("+") ? token.substring(1) : token);
			}

			return Double.parseDouble(token);
		} catch (NumberFormatException e) {
			throw new IOException(Lang.tr("не значение JSON: ") + token);
		}
	}

	private Map<String, Object> object() throws IOException {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++; // {
		skip();

		while (pos < s.length() && s.charAt(pos) != '}') {
			String key;

			if (s.charAt(pos) == '"' || s.charAt(pos) == '\'') {
				key = string();
			} else {
				int start = pos;

				while (pos < s.length() && s.charAt(pos) != ':' && !Character.isWhitespace(s.charAt(pos))) {
					pos++;
				}

				key = s.substring(start, pos);
			}

			skip();

			if (pos >= s.length() || s.charAt(pos) != ':') {
				throw new IOException(Lang.tr("ожидалось ':' после ключа ") + key);
			}

			pos++;
			skip();
			map.put(key, value());
			skip();

			if (pos < s.length() && s.charAt(pos) == ',') {
				pos++;
				skip();
			}
		}

		if (pos >= s.length()) {
			throw new IOException(Lang.tr("не закрыт объект"));
		}

		pos++; // }

		return map;
	}

	private List<Object> array() throws IOException {
		List<Object> list = new ArrayList<>();
		pos++; // [
		skip();

		while (pos < s.length() && s.charAt(pos) != ']') {
			list.add(value());
			skip();

			if (pos < s.length() && s.charAt(pos) == ',') {
				pos++;
				skip();
			}
		}

		if (pos >= s.length()) {
			throw new IOException(Lang.tr("не закрыт массив"));
		}

		pos++; // ]

		return list;
	}

	private String string() throws IOException {
		char quote = s.charAt(pos++);
		StringBuilder sb = new StringBuilder();

		while (pos < s.length() && s.charAt(pos) != quote) {
			char c = s.charAt(pos++);

			if (c == '\\' && pos < s.length()) {
				char e = s.charAt(pos++);

				switch (e) {
				case 'n': sb.append('\n'); break;
				case 't': sb.append('\t'); break;
				case 'r': sb.append('\r'); break;
				case 'b': sb.append('\b'); break;
				case 'f': sb.append('\f'); break;
				case 'u':
					if (pos + 4 > s.length()) {
						throw new IOException(Lang.tr("обрезан \\u-код"));
					}

					sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
					pos += 4;
					break;
				default: sb.append(e);
				}
			} else {
				sb.append(c);
			}
		}

		if (pos >= s.length()) {
			throw new IOException(Lang.tr("не закрыта строка"));
		}

		pos++; // закрывающая кавычка

		return sb.toString();
	}

	// ---------------------------------------------------------------- запись

	static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		write(sb, value, 0);
		sb.append('\n');

		return sb.toString();
	}

	/** Компактная запись одного значения (для редактирования массивов и т.п. одной строкой). */
	static String inline(Object value) {
		StringBuilder sb = new StringBuilder();
		inline(sb, value);

		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder sb, Object v, int depth) {
		if (v instanceof Map) {
			Map<String, Object> map = (Map<String, Object>) v;

			if (map.isEmpty()) {
				sb.append("{}");
				return;
			}

			sb.append("{\n");
			int i = 0;

			for (Map.Entry<String, Object> e : map.entrySet()) {
				indent(sb, depth + 1);
				quote(sb, e.getKey());
				sb.append(": ");
				write(sb, e.getValue(), depth + 1);
				sb.append(++i < map.size() ? ",\n" : "\n");
			}

			indent(sb, depth);
			sb.append('}');
		} else if (v instanceof List) {
			List<Object> list = (List<Object>) v;

			if (list.isEmpty()) {
				sb.append("[]");
				return;
			}

			boolean simple = true;

			for (Object o : list) {
				simple &= !(o instanceof Map) && !(o instanceof List);
			}

			if (simple) {
				inline(sb, v);
				return;
			}

			sb.append("[\n");

			for (int i = 0; i < list.size(); i++) {
				indent(sb, depth + 1);
				write(sb, list.get(i), depth + 1);
				sb.append(i + 1 < list.size() ? ",\n" : "\n");
			}

			indent(sb, depth);
			sb.append(']');
		} else {
			inline(sb, v);
		}
	}

	@SuppressWarnings("unchecked")
	private static void inline(StringBuilder sb, Object v) {
		if (v == null) {
			sb.append("null");
		} else if (v instanceof String) {
			quote(sb, (String) v);
		} else if (v instanceof Double) {
			double d = (Double) v;
			sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.format(java.util.Locale.ROOT, "%.1f", d) : Double.toString(d));
		} else if (v instanceof Map) {
			sb.append('{');
			int i = 0;

			for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
				if (i++ > 0) {
					sb.append(", ");
				}

				quote(sb, e.getKey());
				sb.append(": ");
				inline(sb, e.getValue());
			}

			sb.append('}');
		} else if (v instanceof List) {
			sb.append('[');
			int i = 0;

			for (Object o : (List<Object>) v) {
				if (i++ > 0) {
					sb.append(", ");
				}

				inline(sb, o);
			}

			sb.append(']');
		} else {
			sb.append(v);
		}
	}

	private static void indent(StringBuilder sb, int depth) {
		for (int i = 0; i < depth; i++) {
			sb.append("  ");
		}
	}

	private static void quote(StringBuilder sb, String s) {
		sb.append('"');

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			switch (c) {
			case '"': sb.append("\\\""); break;
			case '\\': sb.append("\\\\"); break;
			case '\n': sb.append("\\n"); break;
			case '\r': sb.append("\\r"); break;
			case '\t': sb.append("\\t"); break;
			default:
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
		}

		sb.append('"');
	}
}
