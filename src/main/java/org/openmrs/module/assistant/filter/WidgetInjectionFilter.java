/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) Okiror Samuel Vinald. All Rights Reserved.
 */
package org.openmrs.module.assistant.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * Injects the assistant floating chat widget script tag into every HTML response, right before
 * the closing &lt;/body&gt; tag. This covers the legacy JSP UI, the uiframework/appui GSP pages,
 * and the React OWA in one place, instead of patching each rendering stack separately.
 */
public class WidgetInjectionFilter implements Filter {

	// Defaults (widget marker, fallback URL, static asset suffixes) live in
	// assistant.properties rather than as literals here, so they can be tuned without touching
	// this class.
	private static final Properties CONFIG = loadConfig();

	private static final String MARKER = CONFIG.getProperty("widget.marker");

	private static final String DEFAULT_WIDGET_URL = CONFIG.getProperty("widget.url.default");

	// Read once at class-load time rather than per-request: the env var comes from the
	// container's environment (set via ASSISTANT_WIDGET_URL in the distro's .env /
	// docker-compose.run.yml) and can't change without a container restart anyway, which
	// already reloads this class.
	private static final String WIDGET_URL = resolveWidgetUrl();

	private static final String SCRIPT_TAG =
		"<script src=\"" + WIDGET_URL + "/widget.js\" data-service-url=\"" + WIDGET_URL + "\" data-"
			+ MARKER + "=\"1\"></script></body>";

	// Extensions that are never HTML documents, so their requests skip buffering entirely.
	// Without this, every static asset on a page (a single OWA load pulls in dozens - JS
	// chunks, CSS, fonts, icons) gets forced through in-memory buffering instead of Tomcat's
	// normal fast static-file path, and under a real browser's parallel request load that
	// exhausts the worker thread pool and produces intermittent 503s.
	private static final String[] STATIC_ASSET_SUFFIXES = CONFIG.getProperty("static.asset.suffixes").split(",");

	private static Properties loadConfig() {
		Properties properties = new Properties();
		try (InputStream in = WidgetInjectionFilter.class.getResourceAsStream("/assistant.properties")) {
			if (in == null) {
				throw new IllegalStateException("assistant.properties not found on the classpath");
			}
			properties.load(in);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to load assistant.properties from the module jar", e);
		}
		return properties;
	}

	private static Charset resolveCharset(String characterEncoding) {
		if (characterEncoding != null) {
			try {
				return Charset.forName(characterEncoding);
			}
			catch (RuntimeException e) {
				// unsupported/unrecognized name - fall through to the default below
			}
		}
		return StandardCharsets.UTF_8;
	}

	private static String resolveWidgetUrl() {
		String url = System.getenv("ASSISTANT_WIDGET_URL");
		if (url == null || url.trim().isEmpty()) {
			return DEFAULT_WIDGET_URL;
		}
		return url.trim().replaceAll("/+$", "");
	}

	@Override
	public void init(FilterConfig filterConfig) {
		// no-op
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
		throws IOException, ServletException {

		if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
			chain.doFilter(request, response);
			return;
		}

		String uri = ((HttpServletRequest) request).getRequestURI();
		String lowerUri = uri == null ? "" : uri.toLowerCase();
		for (String suffix : STATIC_ASSET_SUFFIXES) {
			if (lowerUri.endsWith(suffix)) {
				chain.doFilter(request, response);
				return;
			}
		}

		// OWAs (React/Angular/etc bundles under /owa/) are pre-built static asset trees, not
		// per-request dynamic HTML - buffering their index.html here proved unreliable under
		// real browser concurrent load (intermittent malformed/503 responses that made Chrome
		// silently abandon navigation, breaking every OWA-routed tile - Register Patient, Find
		// Patient, etc). The widget script is baked directly into each OWA's packaged
		// index.html instead (see openmrs-distro-cfl's web/owa/cfl.owa), so this filter doesn't
		// need to touch OWA responses at all.
		if (lowerUri.contains("/owa/")) {
			chain.doFilter(request, response);
			return;
		}

		HttpServletResponse httpResponse = (HttpServletResponse) response;
		BufferingResponseWrapper wrapper = new BufferingResponseWrapper(httpResponse);

		chain.doFilter(request, wrapper);

		String contentType = wrapper.getContentType();
		byte[] body = wrapper.getBuffer();

		if (contentType == null || !contentType.contains("text/html") || body.length == 0) {
			response.getOutputStream().write(body);
			return;
		}

		// Respect the response's actual charset rather than assuming UTF-8: the legacy JSP UI
		// and GSP pages this filter buffers don't all declare (or default to) UTF-8, and
		// decoding/re-encoding with the wrong charset corrupts any non-ASCII characters.
		Charset charset = resolveCharset(wrapper.getCharacterEncoding());
		String html = new String(body, charset);
		int bodyCloseIndex = html.indexOf("</body>");
		if (!html.contains(MARKER) && bodyCloseIndex != -1) {
			html = html.substring(0, bodyCloseIndex) + SCRIPT_TAG + html.substring(bodyCloseIndex + "</body>".length());
		}

		byte[] out = html.getBytes(charset);
		// Deliberately not calling setContentLength: a GZIPFilter earlier in the chain may
		// compress this output before it reaches the client, in which case the on-wire length
		// differs from out.length and an explicit header here would conflict with what the gzip
		// wrapper sends - let the container fall back to chunked transfer encoding instead.
		httpResponse.getOutputStream().write(out);
	}

	@Override
	public void destroy() {
		// no-op
	}

	/**
	 * Buffers the full response body in memory so it can be string-rewritten before being sent
	 * to the client. Response bodies from CFL's own pages are small HTML documents, not large
	 * downloads, so buffering is safe here.
	 */
	private static final class BufferingResponseWrapper extends HttpServletResponseWrapper {

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private final ServletOutputStream out = new ServletOutputStream() {
			@Override
			public void write(int b) {
				buffer.write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) {
				// Without this override, OutputStream's default write(byte[],int,int) calls
				// write(int) once per byte - for multi-MB static assets (the OWA's JS bundles
				// are several MB, with source maps over 20MB) that turns into millions of calls
				// and stalls page loads for tens of seconds.
				buffer.write(b, off, len);
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(WriteListener writeListener) {
				// not needed for synchronous buffering
			}
		};

		private final PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);

		BufferingResponseWrapper(HttpServletResponse response) {
			super(response);
		}

		@Override
		public ServletOutputStream getOutputStream() {
			return out;
		}

		@Override
		public PrintWriter getWriter() {
			return writer;
		}

		@Override
		public void setContentLength(int len) {
			// no-op: injecting the widget script can change the body length after buffering
			// completes, so a length set here (based on the pre-injection body) would go stale.
		}

		@Override
		public void setContentLengthLong(long len) {
			// no-op, see setContentLength
		}

		byte[] getBuffer() {
			writer.flush();
			return buffer.toByteArray();
		}
	}
}
