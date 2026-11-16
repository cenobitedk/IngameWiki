package dev.jan.ingamewiki.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import org.junit.jupiter.api.Test;

class WikiLinksTest {
	private final WikiLinks links = new WikiLinks("https://minecraft.wiki");

	@Test
	void pageUrlsUseUnderscores() {
		assertEquals("https://minecraft.wiki/w/Diamond_Sword", links.page("Diamond Sword", null).toString());
		assertEquals("https://minecraft.wiki/w/Sword_(disambiguation)", links.page("Sword (disambiguation)", null).toString());
		assertEquals("https://minecraft.wiki/w/Java_Edition_1.21#Changes", links.page("Java Edition 1.21", "Changes").toString());
		assertEquals("https://minecraft.wiki/w/Bed%3F", links.page("Bed?", null).toString());
	}

	@Test
	void internalLinks() {
		WikiLinks.Target target = links.parse("/w/Melee_attack#Attack_damage");
		WikiLinks.Target.Page page = assertInstanceOf(WikiLinks.Target.Page.class, target);
		assertEquals("Melee attack", page.title());
		assertEquals("Attack_damage", page.fragment());

		page = assertInstanceOf(WikiLinks.Target.Page.class, links.parse("https://minecraft.wiki/w/Sword_(disambiguation)"));
		assertEquals("Sword (disambiguation)", page.title());
		assertNull(page.fragment());

		page = assertInstanceOf(WikiLinks.Target.Page.class, links.parse("/w/Caf%C3%A9"));
		assertEquals("Café", page.title());
	}

	@Test
	void redLinksPointAtThePage() {
		WikiLinks.Target.Page page = assertInstanceOf(WikiLinks.Target.Page.class,
				links.parse("/index.php?title=Some_Missing_Page&action=edit&redlink=1"));
		assertEquals("Some Missing Page", page.title());
	}

	@Test
	void anchorsAndExternalLinks() {
		WikiLinks.Target.Anchor anchor = assertInstanceOf(WikiLinks.Target.Anchor.class, links.parse("#Crafting%20ingredient"));
		assertEquals("Crafting_ingredient", anchor.fragment());

		WikiLinks.Target.External external = assertInstanceOf(WikiLinks.Target.External.class, links.parse("https://bugs.mojang.com/browse/MC-1"));
		assertEquals(URI.create("https://bugs.mojang.com/browse/MC-1"), external.uri());

		assertInstanceOf(WikiLinks.Target.External.class, links.parse("/w/Special:RecentChanges"));
		assertInstanceOf(WikiLinks.Target.External.class, links.parse("/index.php?title=Diamond&action=history"));
		assertNull(links.parse("mailto:someone@example.com"));
		assertNull(links.parse(""));
	}

	@Test
	void titleFromBrowserUrl() {
		assertEquals("Diamond Sword", links.title(URI.create("https://minecraft.wiki/w/Diamond_Sword#Usage")));
		assertNull(links.title(URI.create("https://example.com/w/Diamond_Sword")));
		assertNull(links.title(URI.create("https://minecraft.wiki/images/Diamond.png")));
	}

	@Test
	void imageUrlsResolveAgainstTheSite() {
		assertEquals("https://minecraft.wiki/images/Diamond_Sword_JE3_BE3.png?24296",
				links.resolve("/images/Diamond_Sword_JE3_BE3.png?24296").toString());
		assertEquals("https://example.org/a.png", links.resolve("//example.org/a.png").toString());
	}

	@Test
	void otherWikiArticlesStayOnThatWiki() {
		WikiLinks matcha = new WikiLinks("https://matchaflavou.red");
		WikiLinks.Target.Page page = assertInstanceOf(WikiLinks.Target.Page.class, matcha.parse("https://minecraft.wiki/w/Spruce_Wood"));
		assertEquals("Spruce Wood", page.title());
		assertEquals("https://minecraft.wiki", page.site());
		assertNull(page.fragment());
		page = assertInstanceOf(WikiLinks.Target.Page.class, matcha.parse("https://minecraft.wiki/w/Oak_Wood#History"));
		assertEquals("Oak Wood", page.title());
		assertEquals("History", page.fragment());
		assertEquals("https://minecraft.wiki", page.site());
		page = assertInstanceOf(WikiLinks.Target.Page.class, matcha.parse("https://de.minecraft.wiki/wiki/Eiche"));
		assertEquals("Eiche", page.title());
		assertEquals("https://de.minecraft.wiki", page.site());
		assertInstanceOf(WikiLinks.Target.External.class, matcha.parse("https://minecraft.wiki/w/Special:RecentChanges"));
		assertInstanceOf(WikiLinks.Target.External.class, matcha.parse("https://minecraft.wiki/"));
		assertInstanceOf(WikiLinks.Target.External.class, matcha.parse("https://bugs.mojang.com/browse/MC-1"));
		assertEquals(URI.create("https://minecraft.wiki/w/Spruce_Wood#History"),
				new WikiRequest.Page("Spruce Wood", "History", "https://minecraft.wiki").webUrl(matcha));
	}

	@Test
	void staticSiteSearchUsesTheSearchPage() {
		WikiLinks matcha = new WikiLinks("https://matchaflavou.red");
		assertEquals("https://matchaflavou.red/index.php?title=Special:Search&go=Go&search=Fishing%20Rod",
				matcha.search("Fishing Rod").toString());
		matcha.staticSite(true);
		assertEquals("https://matchaflavou.red/search/?q=Fishing%20Rod", matcha.search("Fishing Rod").toString());
	}

	@Test
	void subdirectoryWikis() {
		WikiLinks sub = new WikiLinks("https://example.org/wiki");
		assertEquals("https://example.org/wiki/w/Stone", sub.page("Stone", null).toString());
		assertEquals("Stone", sub.title(URI.create("https://example.org/wiki/w/Stone")));
		assertEquals("https://example.org/wiki/api.php", sub.api().toString());
	}

	@Test
	void titleCase() {
		assertEquals("Diamond Sword", WikiLinks.titleCase("diamond_sword"));
		assertEquals("Oak Log", WikiLinks.titleCase("oak_log"));
	}
}
