package dev.jan.ingamewiki.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TitleIndexTest {
	private static final String JSON = """
			[
			  {"t":"Fishing","u":"/w/Fishing","d":"Catching fish.","n":40},
			  {"t":"Tropical Fish","u":"/w/Tropical_Fish","d":"A fish.","n":2},
			  {"t":"Abandoned village","u":"/w/Abandoned_village","d":"A structure & camp.","n":28,"a":["Village"]},
			  {"t":"Advancements","u":"/w/Advancements","d":"Goals.","n":10,"a":[["Overgrown Abbey","Generation"]]},
			  {"t":"Category:Advancements","u":"/w/Category:Advancements","k":"category","n":8},
			  {"t":"Sword","u":"/w/Sword","d":"A weapon.","n":5},
			  {"t":"Iron Sword","u":"/w/Iron_Sword","d":"An iron weapon.","n":5},
			  {"t":"Armor","u":"/w/Armor","d":"Protection.","n":5},
			  {"t":"Mud Kiln","u":"/w/Mud_Kiln","d":"A kiln.","n":5},
			  {"t":"Estus","u":"/w/Estus","d":"A flask.","n":5},
			  {"t":"Estus Ash","u":"/w/Estus_Ash","d":"Ash.","n":5}
			]
			""";

	private final TitleIndex index = TitleIndex.parse(JSON);

	@Test
	void exactTitleBeatsAPrefix() {
		assertEquals("Fishing", index.search("Fishing", 5).getFirst().title());
		assertEquals("Estus", index.search("est", 5).getFirst().title());
	}

	@Test
	void aliasesPointAtThePageAndSection() {
		SearchHit village = index.search("Village", 5).getFirst();
		assertEquals("Abandoned village", village.title());
		assertNull(village.fragment());
		assertTrue(village.snippet().contains("A structure &amp; camp."));
		assertTrue(village.snippet().contains("Redirected from Village"));

		SearchHit abbey = index.search("Overgrown Abbey", 5).getFirst();
		assertEquals("Advancements", abbey.title());
		assertEquals("Generation", abbey.fragment());
	}

	@Test
	void pluralsSpellingOrderAndTypos() {
		assertEquals("Sword", index.search("Swords", 5).getFirst().title());
		assertEquals("Armor", index.search("Armour", 5).getFirst().title());
		assertEquals("Iron Sword", index.search("sword iron", 5).getFirst().title());
		assertEquals("Mud Kiln", index.search("kil", 5).getFirst().title());
		assertEquals("Fishing", index.search("fising", 5).getFirst().title());
		assertEquals("Fishing", index.search("how to fish", 5).getFirst().title());
	}

	@Test
	void categoriesRankBelowTheArticle() {
		assertEquals("Advancements", index.search("Advancements", 5).getFirst().title());
	}

	@Test
	void blankQueryMatchesNothing() {
		assertTrue(index.search("   ", 10).isEmpty());
		assertTrue(index.search("???", 10).isEmpty());
	}

	@Test
	void invalidIndexIsRejected() {
		assertThrows(WikiException.class, () -> TitleIndex.parse("{\"t\":\"Stone\"}"));
		assertThrows(WikiException.class, () -> TitleIndex.parse("not json"));
	}
}
