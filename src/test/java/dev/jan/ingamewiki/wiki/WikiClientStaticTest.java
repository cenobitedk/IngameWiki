package dev.jan.ingamewiki.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WikiClientStaticTest {
	private static final String INDEX = """
			[
			  {"t":"Stone","u":"/w/Stone","d":"A block.","n":10,"a":[["Rock","Uses"]]},
			  {"t":"Abandoned village","u":"/w/Abandoned_village","d":"A structure.","n":4,"a":["Village"]}
			]
			""";

	private static HttpServer server;
	private static ExecutorService executor;
	private static String base;
	private static final ConcurrentMap<String, AtomicInteger> hits = new ConcurrentHashMap<>();

	@BeforeAll
	static void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
			hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
			try {
				if (path.equals("/api.php")) {
					if (query.contains("page=FromApi")) {
						send(exchange, 200, "application/json",
								"{\"parse\":{\"title\":\"FromApi\",\"displaytitle\":\"FromApi\",\"text\":\"<p>From the API.</p>\"}}");
					} else if (query.contains("page=MissingApi")) {
						send(exchange, 200, "application/json",
								"{\"error\":{\"code\":\"missingtitle\",\"info\":\"The page you specified doesn't exist.\"}}");
					} else if (query.contains("page=HtmlApi")) {
						send(exchange, 200, "text/html", "<!DOCTYPE html><title>Not an API</title>");
					} else {
						send(exchange, 404, "text/html", "<html><title>Page not found</title></html>");
					}
					return;
				}
				if (path.equals("/_static/search-titles.json")) {
					send(exchange, 200, "application/json", INDEX);
					return;
				}
				if (path.equals("/w/Old_name")) {
					exchange.getResponseHeaders().set("Location", "/w/Stone#History");
					exchange.sendResponseHeaders(301, -1);
					return;
				}
				if (path.equals("/w/Matcha_Flavoured_Wiki")) {
					exchange.getResponseHeaders().set("Location", "/");
					exchange.sendResponseHeaders(301, -1);
					return;
				}
				if (path.equals("/") || path.isEmpty()) {
					send(exchange, 200, "text/html", page("Matcha Flavoured Wiki", "<p>Welcome.</p>"));
					return;
				}
				if (path.equals("/w/Stone")) {
					send(exchange, 200, "text/html", page("Stone", "<p>A solid block.</p>"));
					return;
				}
				if (path.equals("/w/HtmlApi")) {
					send(exchange, 200, "text/html", page("HtmlApi", "<p>Static fallback.</p>"));
					return;
				}
				send(exchange, 404, "text/html", """
						<html><head><title>Page not found – Test</title></head><body>
						<h1 id="firstHeading">Page not found</h1></body></html>
						""");
			} finally {
				exchange.close();
			}
		});
		server.start();
	}

	@AfterAll
	static void stop() {
		server.stop(0);
		executor.shutdownNow();
	}

	@Test
	void apiJsonIsUsedWhenTheApiAnswers() throws Exception {
		WikiClient client = client();
		WikiPage page = await(client.page("FromApi"));
		assertEquals("FromApi", page.title());
		assertTrue(page.html().contains("From the API."));
		assertFalse(client.links().staticSite());
		assertEquals(0, count("/w/FromApi"));

		ExecutionException missing = assertThrowsExecution(client.page("MissingApi"));
		assertInstanceOf(WikiException.Missing.class, WikiClient.unwrap(missing.getCause()));
		assertEquals(0, count("/w/MissingApi"));
	}

	@Test
	void htmlInsteadOfJsonFallsBackToTheArticle() throws Exception {
		WikiClient client = client();
		WikiPage page = await(client.page("HtmlApi"));
		assertEquals("HtmlApi", page.title());
		assertTrue(page.html().contains("Static fallback."));
		assertTrue(client.links().staticSite());
	}

	@Test
	void staticPagesFollowRedirectsAndSkipTheApiAfterwards() throws Exception {
		WikiClient client = client();
		int apiBefore = count("/api.php");
		WikiPage redirected = await(client.page("Old name"));
		assertEquals("Stone", redirected.title());
		assertEquals("History", redirected.fragment());
		assertTrue(redirected.html().contains("A solid block."));
		assertTrue(client.links().staticSite());
		assertEquals("http://127.0.0.1:" + server.getAddress().getPort() + "/search/?q=Stone",
				client.links().search("Stone").toString());

		WikiPage home = await(client.page("Matcha Flavoured Wiki"));
		assertEquals("Matcha Flavoured Wiki", home.title());
		assertTrue(home.html().contains("Welcome."));

		ExecutionException missing = assertThrowsExecution(client.page("Nope"));
		assertInstanceOf(WikiException.Missing.class, WikiClient.unwrap(missing.getCause()));

		int apiAfterFirst = count("/api.php");
		await(client.page("Stone"));
		assertEquals(apiAfterFirst, count("/api.php"));
		assertTrue(apiAfterFirst > apiBefore);
	}

	@Test
	void pageOnReadsAnotherWikiWithoutSwitchingThisOne() throws Exception {
		HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		other.setExecutor(executor);
		other.createContext("/api.php", exchange -> {
			try {
				send(exchange, 200, "application/json",
						"{\"parse\":{\"title\":\"Spruce Wood\",\"displaytitle\":\"Spruce Wood\",\"text\":\"<p>A wood type.</p>\"}}");
			} finally {
				exchange.close();
			}
		});
		other.start();
		try {
			WikiClient client = client();
			String otherBase = "http://127.0.0.1:" + other.getAddress().getPort();
			WikiPage spruce = await(client.pageOn(new WikiLinks(otherBase), "Spruce Wood"));
			assertEquals("Spruce Wood", spruce.title());
			assertTrue(spruce.html().contains("A wood type."));
			assertFalse(client.links().staticSite());
			assertEquals(0, count("/api.php"));

			WikiPage stone = await(client.pageOn(new WikiLinks(base), "Stone"));
			assertEquals("Stone", stone.title());
			assertTrue(stone.html().contains("A solid block."));
			assertFalse(client.links().staticSite());
		} finally {
			other.stop(0);
		}
	}

	@Test
	void frontPageReadsTheSiteRoot() throws Exception {
		WikiClient client = client();
		ExecutionException missing = assertThrowsExecution(client.page("Minecraft Matcha Wiki"));
		assertInstanceOf(WikiException.Missing.class, WikiClient.unwrap(missing.getCause()));
		WikiPage home = await(client.frontPage());
		assertEquals("Matcha Flavoured Wiki", home.title());
		assertTrue(home.html().contains("Welcome."));
	}

	@Test
	void searchUsesTheTitleIndexAndLookupOpensTheAlias() throws Exception {
		WikiClient client = client();
		int indexBefore = count("/_static/search-titles.json");
		SearchResults results = await(client.search("Village", 5));
		SearchHit village = results.hits().getFirst();
		assertEquals("Abandoned village", village.title());
		assertNull(results.suggestion());
		assertEquals(indexBefore + 1, count("/_static/search-titles.json"));

		await(client.search("Stone", 5));
		assertEquals(indexBefore + 1, count("/_static/search-titles.json"));

		WikiPage rock = await(client.lookup("Rock"));
		assertEquals("Stone", rock.title());
		assertEquals("Uses", rock.fragment());
		assertTrue(rock.html().contains("A solid block."));
	}

	private static WikiClient client() {
		return new WikiClient("IngameWiki/test", new WikiLinks(base));
	}

	private static int count(String path) {
		AtomicInteger count = hits.get(path);
		return count == null ? 0 : count.get();
	}

	private static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(10, TimeUnit.SECONDS);
	}

	private static ExecutionException assertThrowsExecution(CompletableFuture<?> future) throws Exception {
		try {
			future.get(10, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			return e;
		}
		fail("expected the request to fail");
		return null;
	}

	private static String page(String title, String body) {
		return """
				<!DOCTYPE html><html><head><title>%s – Test</title></head><body>
				<div id="mw-navigation">SIDEBAR</div>
				<h1 id="firstHeading"><span class="mw-page-title-main">%s</span></h1>
				<div id="mw-content-text"><div class="mw-parser-output">%s</div></div>
				</body></html>
				""".formatted(title, title, body);
	}

	private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
