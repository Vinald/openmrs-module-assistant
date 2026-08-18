package org.openmrs.module.cflassist.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

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
 * Injects the cfl-assist floating chat widget script tag into every HTML response, right before
 * the closing &lt;/body&gt; tag. This covers the legacy JSP UI, the uiframework/appui GSP pages,
 * and the React OWA in one place, instead of patching each rendering stack separately.
 */
public class WidgetInjectionFilter implements Filter {

	private static final String MARKER = "cflassist-widget";

	private static final String DEFAULT_WIDGET_URL = "http://localhost:4000";

	// Read once at class-load time rather than per-request: the env var comes from the
	// container's environment (set via CFLASSIST_WIDGET_URL in the distro's .env /
	// docker-compose.run.yml) and can't change without a container restart anyway, which
	// already reloads this class.
	private static final String WIDGET_URL = resolveWidgetUrl();

	private static final String SCRIPT_TAG =
		"<script src=\"" + WIDGET_URL + "/widget.js\" data-service-url=\"" + WIDGET_URL + "\" data-"
			+ MARKER + "=\"1\"></script></body>";

	private static String resolveWidgetUrl() {
		String url = System.getenv("CFLASSIST_WIDGET_URL");
		if (url == null || url.trim().isEmpty()) {
			return DEFAULT_WIDGET_URL;
		}
		return url.trim().replaceAll("/+$", "");
	}

	// Extensions that are never HTML documents, so their requests skip buffering entirely.
	// Without this, every static asset on a page (a single OWA load pulls in dozens - JS
	// chunks, CSS, fonts, icons) gets forced through in-memory buffering instead of Tomcat's
	// normal fast static-file path, and under a real browser's parallel request load that
	// exhausts the worker thread pool and produces intermittent 503s.
	private static final String[] STATIC_ASSET_SUFFIXES = { ".js", ".css", ".map", ".json", ".ico", ".png", ".jpg",
		".jpeg", ".gif", ".svg", ".webp", ".woff", ".woff2", ".ttf", ".eot", ".otf", ".pdf", ".zip", ".txt" };

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

		String html = new String(body, StandardCharsets.UTF_8);
		if (!html.contains(MARKER) && html.contains("</body>")) {
			html = html.replaceFirst("</body>", SCRIPT_TAG);
		}

		byte[] out = html.getBytes(StandardCharsets.UTF_8);
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

		byte[] getBuffer() {
			writer.flush();
			return buffer.toByteArray();
		}
	}
}
