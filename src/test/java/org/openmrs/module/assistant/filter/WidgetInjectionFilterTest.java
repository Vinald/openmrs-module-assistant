/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) Okiror Samuel Vinald. All Rights Reserved.
 */
package org.openmrs.module.assistant.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.FilterChain;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

class WidgetInjectionFilterTest {

	private final WidgetInjectionFilter filter = new WidgetInjectionFilter();

	@Test
	void injectsScriptTagBeforeClosingBodyTag() throws Exception {
		HttpServletRequest request = mockRequest("/openmrs/index.htm");
		HttpServletResponse response = mock(HttpServletResponse.class);
		ByteArrayOutputStream captured = captureOutputStream(response);
		when(response.getContentType()).thenReturn("text/html; charset=UTF-8");

		FilterChain chain = writesHtml(response, "<html><body>Hi</body></html>");

		filter.doFilter(request, response, chain);

		String output = captured.toString(StandardCharsets.UTF_8.name());
		assertTrue(output.contains("data-assistant-widget=\"1\""), "expected the widget script tag in: " + output);
		assertTrue(output.indexOf("<script") < output.indexOf("</body>"),
			"expected the script tag before </body> in: " + output);
	}

	@Test
	void doesNotInjectTwiceWhenMarkerAlreadyPresent() throws Exception {
		HttpServletRequest request = mockRequest("/openmrs/index.htm");
		HttpServletResponse response = mock(HttpServletResponse.class);
		ByteArrayOutputStream captured = captureOutputStream(response);
		when(response.getContentType()).thenReturn("text/html");

		String alreadyInjected = "<html><body>Hi<script data-assistant-widget=\"1\"></script></body></html>";
		FilterChain chain = writesHtml(response, alreadyInjected);

		filter.doFilter(request, response, chain);

		assertArrayEquals(alreadyInjected.getBytes(StandardCharsets.UTF_8), captured.toByteArray());
	}

	@Test
	void skipsBufferingForStaticAssets() throws Exception {
		HttpServletRequest request = mockRequest("/scripts/app.js");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).getOutputStream();
	}

	@Test
	void skipsBufferingForOwaRoutes() throws Exception {
		HttpServletRequest request = mockRequest("/owa/cfl/index.html");
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verify(response, never()).getOutputStream();
	}

	private static HttpServletRequest mockRequest(String uri) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn(uri);
		return request;
	}

	private static ByteArrayOutputStream captureOutputStream(HttpServletResponse response) throws IOException {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		ServletOutputStream out = new ServletOutputStream() {

			@Override
			public void write(int b) {
				captured.write(b);
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(WriteListener writeListener) {
			}
		};
		when(response.getOutputStream()).thenReturn(out);
		return captured;
	}

	private static FilterChain writesHtml(HttpServletResponse response, String html) {
		return (req, res) -> {
			res.setContentType("text/html");
			byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
			res.getOutputStream().write(bytes, 0, bytes.length);
		};
	}
}
