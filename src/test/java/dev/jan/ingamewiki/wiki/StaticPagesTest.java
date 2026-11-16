package dev.jan.ingamewiki.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaticPagesTest {
	@Test
	void readsTheArticleAndSkipsTheChrome() {
		WikiPage page = StaticPages.parse("""
				<!DOCTYPE html><html><head><title>Stone – Test</title></head><body>
				<div id="mw-navigation">SIDEBAR</div>
				<h1 id="firstHeading"><span class="mw-page-title-main">Stone</span></h1>
				<div id="mw-content-text"><div class="mw-parser-output"><p>A solid block.</p></div></div>
				</body></html>
				""", "Stone", "Stone", null);
		assertEquals("Stone", page.title());
		assertEquals("Stone", page.displayTitle());
		assertTrue(page.html().contains("A solid block."));
		assertFalse(page.html().contains("SIDEBAR"));
		assertNull(page.fragment());
	}

	@Test
	void rootUrlUsesTheHeading() {
		WikiPage page = StaticPages.parse("""
				<html><head><title>Matcha Flavoured Wiki – Test</title></head><body>
				<h1 id="firstHeading">Matcha Flavoured Wiki</h1>
				<div class="mw-parser-output"><p>Welcome.</p></div>
				</body></html>
				""", "Matcha Flavoured Wiki", null, null);
		assertEquals("Matcha Flavoured Wiki", page.title());
		assertTrue(page.html().contains("Welcome."));
	}

	@Test
	void keepsARedirectFragment() {
		WikiPage page = StaticPages.parse("""
				<html><head><title>Stone – Test</title></head><body>
				<h1 id="firstHeading">Stone</h1>
				<div class="mw-parser-output"><p>A solid block.</p></div>
				</body></html>
				""", "Old name", "Stone", "History");
		assertEquals("Stone", page.title());
		assertEquals("History", page.fragment());
	}

	@Test
	void notFoundPagesAreMissing() {
		String notFound = """
				<html><head><title>Page not found – Test</title></head><body>
				<h1 id="firstHeading">Page not found</h1>
				<div id="mw-content-text"><p>There is no page with this title.</p>
				<div id="mfw-notfound-matches"></div></div>
				</body></html>
				""";
		assertThrows(WikiException.Missing.class, () -> StaticPages.parse(notFound, "Nope", null, null));
	}

	@Test
	void pageWithoutArticleHtmlIsRejected() {
		assertThrows(WikiException.class, () -> StaticPages.parse("<html><title>Stone</title><h1 id=\"firstHeading\">Stone</h1></html>",
				"Stone", "Stone", null));
	}
}
