package dev.jan.ingamewiki.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Title search for a static wiki that publishes {@code /_static/search-titles.json}.
 *
 * <p>The rules match the website's own title box: case, punctuation, British spellings, plurals and small typos,
 * with aliases counted as the page they point at. Full-text matches are not included; the site computes those in the
 * browser.
 */
public final class TitleIndex {
	private static final TitleIndex EMPTY = new TitleIndex(List.of());
	private static final int MIN_SCORE = 330;

	private static final Map<String, String> SPELLING = Map.ofEntries(
			Map.entry("armour", "armor"),
			Map.entry("armoured", "armored"),
			Map.entry("colour", "color"),
			Map.entry("coloured", "colored"),
			Map.entry("colours", "colors"),
			Map.entry("flavour", "flavor"),
			Map.entry("flavoured", "flavored"),
			Map.entry("flavours", "flavors"),
			Map.entry("favour", "favor"),
			Map.entry("favourite", "favorite"),
			Map.entry("grey", "gray"),
			Map.entry("jewellery", "jewelry"),
			Map.entry("sulphur", "sulfur"),
			Map.entry("sulphurous", "sulfurous"),
			Map.entry("stabilised", "stabilized"),
			Map.entry("stabilise", "stabilize"),
			Map.entry("archaeologist", "archeologist"),
			Map.entry("archaeology", "archeology"),
			Map.entry("archaeologists", "archeologists"),
			Map.entry("centre", "center"),
			Map.entry("metre", "meter"),
			Map.entry("fibre", "fiber"),
			Map.entry("mould", "mold"),
			Map.entry("plough", "plow"),
			Map.entry("honour", "honor"),
			Map.entry("savoury", "savory"),
			Map.entry("harbour", "harbor"),
			Map.entry("neighbour", "neighbor"));

	private static final Pattern LEAD = Pattern.compile("^(?:(?:how|where|what|which|who|when|why)"
			+ "(?: (?:do|does|did|can|could|should|would|to|is|are|was|i|you|we|much|many|long))*"
			+ "|(?:can|do|does|is|are) (?:i|you|we)|(?:the )?best(?: way(?: to)?)?|ways? to|guide(?: to| for)?)(?: |$)");
	private static final Pattern VERB = Pattern.compile("^(?:get|getting|make|making|craft|crafting|find|finding|obtain"
			+ "|use|using|build|unlock|farm|breed|tame|cure|kill|beat|defeat|summon|spawn|brew|smelt|grow|upgrade|reach"
			+ "|go|enter|catch)(?: |$)");
	private static final Pattern ARTICLE = Pattern.compile("^(?:a|an|the|some|my)(?: |$)");
	private static final Pattern TAIL = Pattern.compile(
			" (?:do|does|work|works|list|guide|recipe|recipes|wiki|in minecraft|in matcha flavou?red)$");

	private final List<Entry> entries;

	private TitleIndex(List<Entry> entries) {
		this.entries = entries;
	}

	public static TitleIndex empty() {
		return EMPTY;
	}

	/**
	 * @param json the {@code search-titles.json} array
	 */
	public static TitleIndex parse(String json) {
		JsonElement root;
		try {
			root = JsonParser.parseString(json);
		} catch (JsonParseException e) {
			throw new WikiException("Invalid response", e);
		}
		if (!root.isJsonArray()) {
			throw new WikiException("Invalid response");
		}
		List<Entry> entries = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject row = element.getAsJsonObject();
			String title = text(row, "t");
			if (title == null || title.isBlank()) {
				continue;
			}
			String description = text(row, "d");
			entries.add(new Entry(title, description == null ? "" : description, names(row, title), weight(row)));
		}
		return new TitleIndex(List.copyOf(entries));
	}

	/**
	 * Best title and alias matches, highest score first.
	 */
	public List<SearchHit> search(String query, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		List<Variant> forms = variants(query);
		if (forms.getFirst().key().raw().isEmpty()) {
			return List.of();
		}
		List<Scored> found = new ArrayList<>();
		for (Entry entry : entries) {
			double best = 0;
			Name via = null;
			for (Name name : entry.names()) {
				for (Variant form : forms) {
					double scored = score(form.key(), name.key());
					if (form.bonus() != 0 && scored < 950 && !containsAll(name.key().stems(), form.key().stems())) {
						continue;
					}
					if (scored == 0) {
						continue;
					}
					scored += form.bonus() + (name.alias() != null ? -8 : 0);
					if (scored > best) {
						best = scored;
						via = name;
					}
				}
			}
			if (best >= MIN_SCORE && via != null) {
				found.add(new Scored(entry, best + entry.weight(), via.alias(), via.anchor()));
			}
		}
		found.sort((a, b) -> {
			int cmp = Double.compare(b.score(), a.score());
			if (cmp != 0) {
				return cmp;
			}
			cmp = Integer.compare(a.entry().title().length(), b.entry().title().length());
			return cmp != 0 ? cmp : a.entry().title().compareTo(b.entry().title());
		});
		if (found.size() > limit) {
			found = found.subList(0, limit);
		}
		List<SearchHit> hits = new ArrayList<>(found.size());
		for (Scored scored : found) {
			String anchor = scored.anchor();
			String fragment = anchor == null || anchor.isBlank() ? null : WikiLinks.normalizeFragment(anchor);
			hits.add(new SearchHit(scored.entry().title(), snippet(scored.entry().description(), scored.alias()), fragment));
		}
		return List.copyOf(hits);
	}

	private static List<Name> names(JsonObject row, String title) {
		List<Name> names = new ArrayList<>();
		String stripped = title.replaceFirst("^[^:]*:", "");
		names.add(new Name(key(stripped.isEmpty() ? title : stripped), null, null));
		if (title.indexOf(':') > 0) {
			names.add(new Name(key(title), null, null));
		}
		if (!row.has("a") || !row.get("a").isJsonArray()) {
			return names;
		}
		for (JsonElement alias : row.getAsJsonArray("a")) {
			String aliasName;
			String anchor = null;
			if (alias.isJsonArray()) {
				JsonArray pair = alias.getAsJsonArray();
				if (pair.isEmpty() || !pair.get(0).isJsonPrimitive()) {
					continue;
				}
				aliasName = pair.get(0).getAsString();
				if (pair.size() > 1 && pair.get(1).isJsonPrimitive()) {
					anchor = pair.get(1).getAsString();
					if (anchor.isBlank()) {
						anchor = null;
					}
				}
			} else if (alias.isJsonPrimitive()) {
				aliasName = alias.getAsString();
			} else {
				continue;
			}
			if (!aliasName.isBlank()) {
				names.add(new Name(key(aliasName), aliasName, anchor));
			}
		}
		return names;
	}

	private static double weight(JsonObject row) {
		int inbound = 0;
		if (row.has("n") && row.get("n").isJsonPrimitive() && row.get("n").getAsJsonPrimitive().isNumber()) {
			inbound = row.get("n").getAsInt();
		}
		double weight = Math.min(40, 6 * (Math.log(1 + inbound) / Math.log(2)));
		String kind = text(row, "k");
		if (kind == null) {
			return weight;
		}
		return weight + switch (kind) {
			case "category" -> -260;
			case "project" -> -40;
			case "generated" -> -10;
			default -> 0;
		};
	}

	private static List<Variant> variants(String query) {
		List<Variant> out = new ArrayList<>();
		Key full = key(query);
		out.add(new Variant(full, 0));
		String folded = full.raw();
		if (!folded.contains(" ")) {
			return out;
		}
		String stripped = folded;
		String previous;
		do {
			previous = stripped;
			stripped = LEAD.matcher(stripped).replaceFirst("");
			stripped = VERB.matcher(stripped).replaceFirst("");
			stripped = ARTICLE.matcher(stripped).replaceFirst("");
			stripped = TAIL.matcher(stripped).replaceFirst("");
		} while (!stripped.equals(previous) && !stripped.isEmpty());
		if (stripped.isEmpty() || stripped.equals(folded)) {
			return out;
		}
		out.add(new Variant(key(stripped), -20));
		if ((folded.startsWith("how ") || folded.startsWith("what ") || folded.startsWith("where ")) && !stripped.contains(" ")) {
			String ing = stripped.endsWith("e") && !stripped.endsWith("ee")
					? stripped.substring(0, stripped.length() - 1) + "ing"
					: stripped + "ing";
			out.add(new Variant(key(ing), -10));
			out.add(new Variant(key(stripped + stripped.charAt(stripped.length() - 1) + "ing"), -15));
		}
		return out;
	}

	/**
	 * How well {@code query} matches {@code name}. Rough bands: 1000 exact, 970 plurals, 800 the name starts with the
	 * query, 700 its words do, 600 one typo in the whole name, 500 the query mentions the whole name.
	 */
	private static double score(Key query, Key name) {
		if (query.raw().isEmpty() || name.raw().isEmpty()) {
			return 0;
		}
		if (query.raw().equals(name.raw())) {
			return 1000;
		}
		if (query.stem().equals(name.stem())) {
			return 970;
		}
		if (query.compact().length() >= 4 && query.compact().equals(name.compact())) {
			return 950;
		}
		int extra = Math.min(100, 4 * (name.raw().length() - query.raw().length()) + 12 * (name.words().size() - query.words().size()));
		if (name.raw().startsWith(query.raw())) {
			return 800 - extra;
		}
		if (query.compact().length() >= 4 && name.compact().startsWith(query.compact())) {
			return 780 - extra;
		}
		double best = 0;
		boolean[] used = new boolean[name.words().size()];
		boolean matched = true;
		int moved = 0;
		for (int i = 0; i < query.stems().size() && matched; i++) {
			matched = false;
			for (int j = 0; j < name.stems().size(); j++) {
				if (used[j]) {
					continue;
				}
				boolean last = i == query.stems().size() - 1;
				if (name.stems().get(j).equals(query.stems().get(i))
						|| name.words().get(j).equals(query.words().get(i))
						|| (last && name.words().get(j).startsWith(query.words().get(i)))) {
					used[j] = true;
					matched = true;
					if (j != i) {
						moved++;
					}
					break;
				}
			}
		}
		if (matched) {
			best = 700 - 15L * (name.words().size() - query.words().size()) - 10L * moved;
		}
		int whole = distance(query.raw(), name.raw(), allowed(query.raw().length()));
		if (whole <= allowed(query.raw().length())) {
			best = Math.max(best, 600 - 80L * whole);
		}
		int compact = distance(query.compact(), name.compact(), allowed(query.compact().length()));
		if (compact <= allowed(query.compact().length())) {
			best = Math.max(best, 590 - 80L * compact);
		}
		if (query.stems().size() > name.stems().size() && containsAll(query.stems(), name.stems())) {
			best = Math.max(best, 500 + 25L * name.stems().size() - 10L * (query.stems().size() - name.stems().size()));
		}
		if (best == 0 || best < 450) {
			int total = 0;
			used = new boolean[name.words().size()];
			matched = true;
			for (int i = 0; i < query.words().size() && matched; i++) {
				String word = query.words().get(i);
				int max = allowed(word.length());
				int found = -1;
				int nearest = max + 1;
				for (int j = 0; j < name.words().size(); j++) {
					if (used[j]) {
						continue;
					}
					String nameWord = name.words().get(j);
					int wordDistance = distance(word, nameWord, max);
					if (i == query.words().size() - 1 && nameWord.length() > word.length()) {
						wordDistance = Math.min(wordDistance, distance(word, nameWord.substring(0, word.length()), max));
					}
					if (wordDistance < nearest) {
						nearest = wordDistance;
						found = j;
					}
				}
				if (found < 0 || nearest > max) {
					matched = false;
				} else {
					used[found] = true;
					total += nearest;
				}
			}
			if (matched && total > 0) {
				best = Math.max(best, 450 - 60L * total - 15L * (name.words().size() - query.words().size()));
			}
		}
		return Math.max(0, best);
	}

	private static int allowed(int length) {
		if (length < 4) {
			return 0;
		}
		if (length < 7) {
			return 1;
		}
		return length < 11 ? 2 : 3;
	}

	/**
	 * Damerau-Levenshtein distance, or {@code max + 1} once it is clear the distance exceeds {@code max}.
	 */
	private static int distance(String a, String b, int max) {
		if (Math.abs(a.length() - b.length()) > max) {
			return max + 1;
		}
		int[] previous2 = null;
		int[] previous = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			int[] current = new int[b.length() + 1];
			current[0] = i;
			int best = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				int value = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
				if (previous2 != null && i > 1 && j > 1
						&& a.charAt(i - 1) == b.charAt(j - 2)
						&& a.charAt(i - 2) == b.charAt(j - 1)) {
					value = Math.min(value, previous2[j - 2] + 1);
				}
				current[j] = value;
				if (value < best) {
					best = value;
				}
			}
			if (best > max) {
				return max + 1;
			}
			previous2 = previous;
			previous = current;
		}
		return previous[b.length()];
	}

	private static Key key(String value) {
		List<String> words = new ArrayList<>();
		for (String part : fold(value).split(" ")) {
			if (!part.isEmpty()) {
				words.add(SPELLING.getOrDefault(part, part));
			}
		}
		List<String> stems = words.stream().map(TitleIndex::stem).toList();
		return new Key(String.join(" ", words), String.join(" ", stems), String.join("", words), List.copyOf(words), stems);
	}

	private static String fold(String value) {
		String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036F]", "");
		return decomposed.toLowerCase(Locale.ROOT)
				.replace("&", " and ")
				.replaceAll("['’‘`´]", "")
				.replaceAll("[^a-z0-9]+", " ")
				.trim();
	}

	private static String stem(String word) {
		if (word.length() <= 3 || word.chars().anyMatch(Character::isDigit)) {
			return word;
		}
		if (word.endsWith("ies")) {
			return word.substring(0, word.length() - 3) + "y";
		}
		if (word.endsWith("ss") || word.endsWith("us") || word.endsWith("is") || word.endsWith("os") || word.endsWith("as")) {
			return word;
		}
		return word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
	}

	private static boolean containsAll(List<String> haystack, List<String> needles) {
		for (String needle : needles) {
			if (!haystack.contains(needle)) {
				return false;
			}
		}
		return true;
	}

	private static String snippet(String description, @Nullable String alias) {
		StringBuilder html = new StringBuilder();
		if (!description.isBlank()) {
			html.append(escape(description.strip()));
		}
		if (alias != null && !alias.isBlank()) {
			if (!html.isEmpty()) {
				html.append("<br>");
			}
			html.append("Redirected from ").append(escape(alias));
		}
		return html.toString();
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static @Nullable String text(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
	}

	private record Key(String raw, String stem, String compact, List<String> words, List<String> stems) {
	}

	private record Variant(Key key, int bonus) {
	}

	private record Name(Key key, @Nullable String alias, @Nullable String anchor) {
	}

	private record Entry(String title, String description, List<Name> names, double weight) {
	}

	private record Scored(Entry entry, double score, @Nullable String alias, @Nullable String anchor) {
	}
}
