package dev.jan.ingamewiki.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads pages from the configured wiki. A MediaWiki action API is used when {@code <wiki>/api.php} answers; otherwise
 * pages are read from the static {@code /w/<title>} HTML and search uses {@code /_static/search-titles.json}.
 */
public final class WikiClient {
	private static final Logger LOGGER = LoggerFactory.getLogger(WikiClient.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final String userAgent;
	private final Object state = new Object();
	private volatile WikiLinks links;
	private volatile boolean staticSite;
	private @Nullable CompletableFuture<TitleIndex> titles;

	public WikiClient(String userAgent, WikiLinks links) {
		this.userAgent = userAgent;
		this.links = links;
	}

	public WikiLinks links() {
		return links;
	}

	public void setLinks(WikiLinks links) {
		synchronized (state) {
			this.staticSite = false;
			this.titles = null;
			this.links = links;
		}
	}

	/**
	 * Fetches the rendered HTML of a page, following redirects.
	 *
	 * @throws WikiException.Missing (wrapped) if the page does not exist
	 */
	public CompletableFuture<WikiPage> page(String title) {
		if (staticSite) {
			return staticPage(title);
		}
		WikiLinks site = links;
		return apiPage(title).exceptionallyCompose(error -> {
			if (!(unwrap(error) instanceof NoApi)) {
				return CompletableFuture.failedFuture(error);
			}
			useStatic(site);
			if (links != site) {
				return CompletableFuture.failedFuture(error);
			}
			return staticPage(title);
		});
	}

	/**
	 * Full-text search on a MediaWiki site, or title and alias search on a static one.
	 */
	public CompletableFuture<SearchResults> search(String query, int limit) {
		if (staticSite) {
			return staticSearch(query, limit);
		}
		WikiLinks site = links;
		return apiSearch(query, limit).exceptionallyCompose(error -> {
			if (!(unwrap(error) instanceof NoApi)) {
				return CompletableFuture.failedFuture(error);
			}
			useStatic(site);
			if (links != site) {
				return CompletableFuture.failedFuture(error);
			}
			return staticSearch(query, limit);
		});
	}

	/**
	 * Loads the page called {@code query}, or, if there is none, the best search match.
	 */
	public CompletableFuture<WikiPage> lookup(String query) {
		return page(query).exceptionallyCompose(error -> {
			if (!(unwrap(error) instanceof WikiException.Missing)) {
				return CompletableFuture.failedFuture(error);
			}
			return search(query, 1).thenCompose(results -> {
				if (!results.hits().isEmpty()) {
					return open(results.hits().getFirst());
				}
				if (results.suggestion() != null && !results.suggestion().equalsIgnoreCase(query)) {
					return search(results.suggestion(), 1).thenCompose(suggested -> suggested.hits().isEmpty()
							? CompletableFuture.failedFuture(new WikiException.Missing(query))
							: open(suggested.hits().getFirst()));
				}
				return CompletableFuture.failedFuture(new WikiException.Missing(query));
			});
		});
	}

	public CompletableFuture<byte[]> bytes(URI uri) {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("User-Agent", userAgent)
				.GET()
				.build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new WikiException("HTTP " + response.statusCode() + " for " + uri);
			}
			return response.body();
		});
	}

	private CompletableFuture<WikiPage> open(SearchHit hit) {
		return page(hit.title()).thenApply(found -> hit.fragment() == null
				? found
				: new WikiPage(found.title(), found.displayTitle(), found.html(), hit.fragment()));
	}

	private CompletableFuture<WikiPage> apiPage(String title) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("action", "parse");
		params.put("page", title);
		params.put("prop", "text|displaytitle");
		params.put("redirects", "1");
		params.put("disableeditsection", "1");
		params.put("disablelimitreport", "1");
		params.put("disabletoc", "1");
		return api(params).thenApply(json -> {
			JsonObject error = object(json, "error");
			if (error != null) {
				if ("missingtitle".equals(string(error, "code")) || "invalidtitle".equals(string(error, "code"))) {
					throw new WikiException.Missing(title);
				}
				throw new WikiException(string(error, "info") != null ? string(error, "info") : "API error");
			}
			JsonObject parse = object(json, "parse");
			if (parse == null) {
				throw new WikiException("Unexpected response");
			}
			String fragment = null;
			JsonArray redirects = parse.has("redirects") ? parse.getAsJsonArray("redirects") : null;
			if (redirects != null && !redirects.isEmpty()) {
				fragment = string(redirects.get(redirects.size() - 1).getAsJsonObject(), "tofragment");
			}
			String canonical = string(parse, "title");
			String display = string(parse, "displaytitle");
			return new WikiPage(
					canonical == null ? title : canonical,
					display == null ? (canonical == null ? title : canonical) : stripTags(display),
					string(parse, "text") == null ? "" : string(parse, "text"),
					fragment == null ? null : WikiLinks.normalizeFragment(fragment));
		});
	}

	private CompletableFuture<SearchResults> apiSearch(String query, int limit) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("action", "query");
		params.put("list", "search");
		params.put("srsearch", query);
		params.put("srlimit", Integer.toString(limit));
		params.put("srprop", "snippet");
		params.put("srnamespace", "0");
		return api(params).thenApply(json -> {
			JsonObject q = object(json, "query");
			List<SearchHit> hits = new ArrayList<>();
			String suggestion = null;
			if (q != null) {
				JsonObject info = object(q, "searchinfo");
				if (info != null) {
					suggestion = string(info, "suggestion");
				}
				if (q.has("search")) {
					for (JsonElement e : q.getAsJsonArray("search")) {
						JsonObject hit = e.getAsJsonObject();
						String t = string(hit, "title");
						if (t != null) {
							hits.add(new SearchHit(t, string(hit, "snippet") == null ? "" : string(hit, "snippet")));
						}
					}
				}
			}
			return new SearchResults(query, hits, suggestion);
		});
	}

	private CompletableFuture<WikiPage> staticPage(String title) {
		WikiLinks site = links;
		URI uri = site.page(title, null);
		return send(uri, "text/html").thenApply(response -> {
			if (response.statusCode() == 404) {
				throw new WikiException.Missing(title);
			}
			if (response.statusCode() != 200) {
				throw new WikiException("HTTP " + response.statusCode() + " for " + uri);
			}
			return StaticPages.parse(response.body(), title, site.title(response.uri()), redirectFragment(response));
		});
	}

	private CompletableFuture<SearchResults> staticSearch(String query, int limit) {
		return titleIndex().thenApply(index -> new SearchResults(query, index.search(query, limit), null));
	}

	private CompletableFuture<TitleIndex> titleIndex() {
		synchronized (state) {
			if (titles != null) {
				return titles;
			}
			WikiLinks site = links;
			URI uri = URI.create(site.base() + "/_static/search-titles.json");
			CompletableFuture<TitleIndex> loading = new CompletableFuture<>();
			titles = loading;
			send(uri, "application/json").whenComplete((response, error) -> {
				if (error != null) {
					synchronized (state) {
						if (titles == loading) {
							titles = null;
						}
					}
					loading.completeExceptionally(error);
					return;
				}
				try {
					String body = response.body().stripLeading();
					loading.complete(response.statusCode() == 200 && body.startsWith("[")
							? TitleIndex.parse(body)
							: TitleIndex.empty());
				} catch (RuntimeException e) {
					synchronized (state) {
						if (titles == loading) {
							titles = null;
						}
					}
					loading.completeExceptionally(e);
				}
			});
			return loading;
		}
	}

	private CompletableFuture<JsonObject> api(Map<String, String> params) {
		StringBuilder query = new StringBuilder("format=json&formatversion=2&maxlag=5");
		for (Map.Entry<String, String> e : params.entrySet()) {
			query.append('&').append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		WikiLinks site = links;
		URI uri = URI.create(site.api() + "?" + query);
		return send(uri, "application/json").thenApply(response -> {
			if (response.statusCode() == 404 || response.statusCode() == 405 || looksLikeHtml(response)) {
				useStatic(site);
				throw new NoApi();
			}
			if (response.statusCode() != 200) {
				throw new WikiException("HTTP " + response.statusCode());
			}
			try {
				return JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (JsonParseException | IllegalStateException e) {
				throw new WikiException("Invalid response", e);
			}
		});
	}

	private CompletableFuture<HttpResponse<String>> send(URI uri, String accept) {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("User-Agent", userAgent)
				.header("Accept", accept)
				.GET()
				.build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private void useStatic(WikiLinks site) {
		synchronized (state) {
			if (links != site) {
				return;
			}
			boolean already = staticSite;
			staticSite = true;
			site.staticSite(true);
			if (!already) {
				LOGGER.info("No MediaWiki API at {}, reading static pages", site.base());
			}
		}
	}

	private static boolean looksLikeHtml(HttpResponse<String> response) {
		if (response.statusCode() != 200) {
			return false;
		}
		String body = response.body().stripLeading();
		return body.startsWith("<");
	}

	/**
	 * Section named by the nearest redirect that carried a fragment. Clients drop fragments from the followed request,
	 * but the {@code Location} header still has one.
	 */
	private static @Nullable String redirectFragment(HttpResponse<?> response) {
		for (HttpResponse<?> hop = previous(response); hop != null; hop = previous(hop)) {
			Optional<String> location = hop.headers().firstValue("location");
			if (location.isEmpty()) {
				continue;
			}
			int hash = location.get().indexOf('#');
			if (hash < 0) {
				continue;
			}
			String raw = location.get().substring(hash + 1);
			return raw.isEmpty() ? null : WikiLinks.normalizeFragment(raw);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static @Nullable HttpResponse<?> previous(HttpResponse<?> response) {
		Optional<? extends HttpResponse<?>> previous = (Optional<? extends HttpResponse<?>>) (Optional<?>) response.previousResponse();
		return previous.orElse(null);
	}

	public static Throwable unwrap(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	private static @Nullable JsonObject object(JsonObject json, String key) {
		JsonElement e = json.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static @Nullable String string(JsonObject json, String key) {
		JsonElement e = json.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static String stripTags(String html) {
		return org.jsoup.Jsoup.parse(html).text();
	}

	/**
	 * {@code api.php} did not answer as a MediaWiki API. Callers fall back to static pages.
	 */
	private static final class NoApi extends WikiException {
		private NoApi() {
			super("No MediaWiki API");
		}
	}
}
