package dev.jan.ingamewiki.wiki;

import org.jspecify.annotations.Nullable;

/**
 * One search result.
 *
 * @param snippet  HTML excerpt. MediaWiki wraps matches in {@code <span class="searchmatch">}; a static wiki uses the
 *                 page's short description
 * @param fragment section an alias points at, if any
 */
public record SearchHit(String title, String snippet, @Nullable String fragment) {
	public SearchHit(String title, String snippet) {
		this(title, snippet, null);
	}
}
