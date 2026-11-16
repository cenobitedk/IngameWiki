package dev.jan.ingamewiki.wiki;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;

/**
 * Reads a page out of the HTML a static wiki publishes at {@code /w/<title>}.
 */
public final class StaticPages {
	private StaticPages() {
	}

	/**
	 * @param urlTitle  title decoded from the final URL after redirects, or {@code null} when the URL is the site root
	 * @param fragment  section a redirect pointed at, if any
	 * @throws WikiException.Missing when the document is the site's "page not found" page
	 */
	public static WikiPage parse(String html, String requestedTitle, @Nullable String urlTitle, @Nullable String fragment) {
		Document doc = Jsoup.parse(html);
		String docTitle = doc.title() == null ? "" : doc.title().strip();
		Element headingEl = doc.selectFirst("#firstHeading");
		String heading = headingEl == null ? "" : headingEl.text().strip();
		if (isMissing(doc, docTitle, heading)) {
			throw new WikiException.Missing(requestedTitle);
		}
		Element output = doc.selectFirst(".mw-parser-output");
		if (output == null) {
			throw new WikiException("Unexpected response");
		}
		String canonical = urlTitle != null && !urlTitle.isBlank() ? urlTitle : heading;
		if (canonical.isBlank()) {
			canonical = requestedTitle;
		}
		String display = heading.isBlank() ? canonical : heading;
		String section = fragment == null || fragment.isBlank() ? null : fragment;
		return new WikiPage(canonical, display, output.html(), section);
	}

	private static boolean isMissing(Document doc, String docTitle, String heading) {
		if (doc.selectFirst("#mfw-notfound-matches") != null) {
			return true;
		}
		if (heading.equalsIgnoreCase("Page not found")) {
			return true;
		}
		return docTitle.regionMatches(true, 0, "Page not found", 0, "Page not found".length());
	}
}
