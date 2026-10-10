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
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;

/**
 * Сетевая часть для Modrinth: соединение с учётом прокси из переменных окружения ({@code HTTPS_PROXY},
 * {@code ALL_PROXY}) — Java сама их не читает, а у многих доступ к Modrinth идёт именно через прокси или VPN.
 */
final class ModrinthNet {
	private ModrinthNet() { }

	static HttpURLConnection open(String url, int connectTimeoutMs, int readTimeoutMs) throws IOException {
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
