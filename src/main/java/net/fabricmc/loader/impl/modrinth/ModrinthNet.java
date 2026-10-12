/*
 * Copyright (c) 2026 GanichGG. All rights reserved.
 *
 * This file is part of ArgentumLoader. It is not licensed for use, copying,
 * modification or distribution except with the written permission of the
 * copyright holder. See LICENSE.
 */

package net.fabricmc.loader.impl.modrinth;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;

/**
 * Сетевая часть для Modrinth: соединение с учётом прокси из переменных окружения ({@code HTTPS_PROXY},
 * {@code ALL_PROXY}) — Java сама их не читает, а у многих доступ к Modrinth идёт именно через прокси или VPN.
 */
public final class ModrinthNet {
	private ModrinthNet() { }

	public static HttpURLConnection open(String url, int connectTimeoutMs, int readTimeoutMs) throws IOException {
		URL u = new URL(url);
		Proxy proxy = envProxy();
		HttpURLConnection connection = (HttpURLConnection) (proxy == null ? u.openConnection() : u.openConnection(proxy));
		connection.setConnectTimeout(connectTimeoutMs);
		connection.setReadTimeout(readTimeoutMs);

		return connection;
	}

	/** Прокси из окружения, если Java-свойства прокси не заданы; null — идём напрямую. */
	private static Proxy envProxy() {
		if (System.getProperty("https.proxyHost") != null || System.getProperty("socksProxyHost") != null) {
			return null; // пользователь настроил прокси стандартными свойствами JVM
		}

		for (String name : new String[] {"HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"}) {
			String value = System.getenv(name);

			if (value == null || value.trim().isEmpty()) {
				continue;
			}

			try {
				String v = value.trim();
				URI uri = URI.create(v.contains("://") ? v : "http://" + v);

				if (uri.getHost() == null) {
					continue;
				}

				boolean socks = uri.getScheme() != null && uri.getScheme().startsWith("socks");
				int port = uri.getPort() > 0 ? uri.getPort() : socks ? 1080 : 80;

				return new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP, new InetSocketAddress(uri.getHost(), port));
			} catch (IllegalArgumentException e) {
				// кривое значение переменной — пробуем следующую
			}
		}

		return null;
	}
}
