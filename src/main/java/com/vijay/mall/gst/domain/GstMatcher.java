package com.vijay.mall.gst.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Finds the HSN code and GST rate for a product name. Pure Java (no Spring, no database): build one instance from the rows
 * of {@code gst_hsn_rate} and {@code gst_keyword} (the service does that at start-up and after every admin edit) and call
 * {@link #suggest}. The instance is immutable once built and safe to share between request threads.
 *
 * <h2>How a name is matched</h2>
 * <ol>
 *   <li><b>Tokens.</b> Lower-case, split into letters/digits (any script, so Gujarati works), drop 1-character pieces, fold
 *       spelling variants through {@link #SYNONYMS} (daal/dhal -> dal, zeera/jira -> cumin ...). Done the same way for the
 *       index and for the query. For the QUERY only, pack sizes ("5kg", "500", "ml") and filler words are dropped.</li>
 *   <li><b>Index.</b> For every keyword row: the tokens of its name, and of name + keywords + Gujarati name. A token's
 *       document frequency (how many rows use it) makes rare words (basmati) count more than common ones (powder).</li>
 *   <li><b>Spelling tolerance.</b> A query word stands for index words: exact 1.0, plural 0.95 / 0.9, prefix 0.65 (3+ letters),
 *       one typo 0.7 (words of 4+ letters), two typos 0.55 (words of 8+ letters). Typos are Levenshtein distances.</li>
 *   <li><b>Score of a keyword row</b> = sum over query words of the best weight x (1 + 2/df) (+ weight again when the word is
 *       in the row's NAME) x (0.5 + 0.5 x share of query words that hit), plus bonuses: the row name appears as a phrase in
 *       the query (+4 + name words), the whole keyword list appears as a phrase (+3), every name word is in the query (+2),
 *       every query word is a keyword of the row (+2, the "Hindi word alone" rule), the category matches the row's
 *       department or subcategory (+1.5), the row is VERIFIED (+0.05, a tie-break only).</li>
 *   <li><b>Confidence.</b> {@code score = raw / (raw + 2.5)}, so the API threshold 0.5 equals raw 2.5. Rows below 35 % of the
 *       best raw score are not offered as alternatives; alternatives are one per HSN code.</li>
 *   <li><b>Which row of the HSN</b> (see {@link #resolve}): a forced condition key on the keyword wins; else an {@code ANY}
 *       row; else packed/loose rows (the caller's {@code packed}, else loose/packed words in the name, else packed first);
 *       else price-band rows (the caller's {@code price}, else the lower band). {@code choices} are the other rows of the
 *       same group when their rates differ.</li>
 * </ol>
 * The Python twin {@code verify_matcher.py} (kept in the handover package, not in this repository) was used to calibrate the constants; the
 * table-driven cases in {@code src/test/resources/gst/cases.tsv} are checked by both.
 */
public final class GstMatcher {
  /** Raw score at which the normalised score is 0.5 (the API threshold). */
  static final double CUT = 2.5;
  static final double COVER_BONUS = 2.0;
  static final double VERIFIED_BONUS = 0.05;
  /** Alternatives must reach this share of the best raw score. */
  static final double ALT_SHARE = 0.35;

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
  private static final Pattern SIZE = Pattern.compile("\\d+(kg|kgs|gm|gms|g|ml|l|ltr|lt|pc|pcs|x)?");
  private static final Set<String> NOISE = Set.of("kg", "kgs", "gm", "gms", "gram", "grams", "ml", "ltr", "lt", "litre",
      "liter", "litres", "liters", "pc", "pcs", "offer", "new", "size", "of", "and", "the", "for", "with");
  private static final Set<String> LOOSE_WORDS = Set.of("loose", "unpacked", "khula", "open");
  private static final Set<String> PACKED_WORDS = Set.of("packed", "packet", "prepacked", "labelled", "labeled", "sealed");

  /**
   * Spelling variants folded to one form, in the index and in the query. Variants only (daal -> dal), plus the Hindi and
   * Gujarati names whose English twin is already in the keyword file (jeera -> cumin). Add a line when a spelling does not
   * match; moving this map to a table is a later option. Keys must be lower case.
   */
  static final Map<String, String> SYNONYMS = Map.ofEntries(
      Map.entry("daal", "dal"), Map.entry("dhal", "dal"), Map.entry("dall", "dal"), Map.entry("dhaal", "dal"),
      Map.entry("dalia", "daliya"), Map.entry("dalya", "daliya"),
      Map.entry("aata", "atta"), Map.entry("ata", "atta"), Map.entry("aatta", "atta"), Map.entry("attaa", "atta"),
      Map.entry("jeera", "cumin"), Map.entry("jira", "cumin"), Map.entry("zeera", "cumin"), Map.entry("jiru", "cumin"),
      Map.entry("jeeraa", "cumin"), Map.entry("zira", "cumin"), Map.entry("jeru", "cumin"),
      Map.entry("haldi", "turmeric"), Map.entry("halad", "turmeric"), Map.entry("haldar", "turmeric"),
      Map.entry("haldee", "turmeric"),
      Map.entry("dhania", "coriander"), Map.entry("dhaniya", "coriander"), Map.entry("dhaaniya", "coriander"),
      Map.entry("dhana", "coriander"),
      Map.entry("masalo", "masala"), Map.entry("masaala", "masala"), Map.entry("masalaa", "masala"),
      Map.entry("namkin", "namkeen"), Map.entry("nankeen", "namkeen"), Map.entry("namkeens", "namkeen"),
      Map.entry("biscuits", "biscuit"), Map.entry("biskut", "biscuit"), Map.entry("biscut", "biscuit"),
      Map.entry("biskit", "biscuit"), Map.entry("biskuit", "biscuit"),
      Map.entry("chaawal", "chawal"), Map.entry("chaval", "chawal"), Map.entry("chawl", "chawal"),
      Map.entry("ghi", "ghee"), Map.entry("gee", "ghee"), Map.entry("gheee", "ghee"),
      Map.entry("shampu", "shampoo"), Map.entry("shampooo", "shampoo"), Map.entry("shampo", "shampoo"),
      Map.entry("teel", "til"), Map.entry("tel", "oil"),
      Map.entry("mirchi", "mirch"), Map.entry("mirchee", "mirch"),
      Map.entry("panir", "paneer"), Map.entry("paner", "paneer"), Map.entry("panner", "paneer"),
      Map.entry("toor", "tuvar"), Map.entry("tur", "tuvar"), Map.entry("arhar", "tuvar"),
      Map.entry("cheeni", "sugar"), Map.entry("chini", "sugar"), Map.entry("shakkar", "sugar"),
      Map.entry("doodh", "milk"), Map.entry("dudh", "milk"),
      Map.entry("sabun", "soap"), Map.entry("saboon", "soap"),
      Map.entry("tshirt", "shirt"));

  /** One answer: the HSN row, the item name that matched, the 0..1 confidence and the verification to show. */
  public record Hit(GstRow.Hsn row, String matchedName, double score, String verification) {}

  /** The answer to {@link #suggest}: {@code best} is null when nothing reaches the threshold. */
  public record Result(String query, Hit best, List<Hit> choices, List<Hit> alternatives) {
    static Result none(String query) { return new Result(query, null, List.of(), List.of()); }
  }

  private record Doc(GstRow.Keyword kw, Set<String> nameTokens, Set<String> keywordTokens, String namePhrase,
                     String keywordPhrase, Set<String> deptTokens) {}

  private record Scored(int index, double raw) {}

  record Resolved(GstRow.Hsn row, List<GstRow.Hsn> choices) {}

  private final List<GstRow.Hsn> hsnRows;
  private final Map<String, List<GstRow.Hsn>> byCode = new HashMap<>();
  private final List<Doc> docs = new ArrayList<>();
  private final Map<String, Integer> df = new LinkedHashMap<>();
  private final Map<String, List<Integer>> index = new HashMap<>();
  private final Map<Integer, List<String>> vocabByLength = new HashMap<>();
  private final List<Set<String>> hsnTokens = new ArrayList<>();
  /** Spelling expansions per typed word; typed words are unbounded, so {@link #expand} empties it past 20,000 entries. */
  private final ConcurrentHashMap<String, List<Weighted>> expandCache = new ConcurrentHashMap<>();

  private record Weighted(String token, double weight) {}

  /**
   * Builds the index. Inactive rows are ignored. Keyword rows whose HSN code has no active row are ignored (the seed
   * migration guarantees there are none; an admin edit must not create one).
   */
  public GstMatcher(Collection<GstRow.Hsn> hsn, Collection<GstRow.Keyword> keywords) {
    List<GstRow.Hsn> rows = new ArrayList<>();
    for (GstRow.Hsn h : hsn) if (h.active()) rows.add(h);
    rows.sort(Comparator.comparing(GstRow.Hsn::hsn).thenComparingInt(GstRow.Hsn::rowNo));
    this.hsnRows = Collections.unmodifiableList(rows);
    for (GstRow.Hsn h : rows) byCode.computeIfAbsent(h.hsn(), k -> new ArrayList<>()).add(h);
    for (List<GstRow.Hsn> l : byCode.values()) l.sort(Comparator.comparingInt(GstRow.Hsn::rowNo));

    List<GstRow.Keyword> kws = new ArrayList<>();
    for (GstRow.Keyword k : keywords) if (byCode.containsKey(k.hsn())) kws.add(k);
    kws.sort(Comparator.comparingInt(GstRow.Keyword::rowNo));
    Map<String, Set<String>> namesByHsn = new HashMap<>();
    for (GstRow.Keyword k : kws) {
      List<String> nameTok = tokens(k.name() + " " + nz(k.nameGu()));
      Set<String> nt = new LinkedHashSet<>(nameTok);
      Set<String> kt = new LinkedHashSet<>(tokens(nz(k.keywords()) + " " + k.name() + " " + nz(k.nameGu())));
      Set<String> dept = new LinkedHashSet<>(tokens(nz(k.department()) + " " + nz(k.subcategory())));
      int i = docs.size();
      docs.add(new Doc(k, nt, kt, String.join(" ", tokens(k.name())), String.join(" ", tokens(nz(k.keywords()))), dept));
      for (String t : kt) {
        df.merge(t, 1, Integer::sum);
        index.computeIfAbsent(t, x -> new ArrayList<>()).add(i);
      }
      namesByHsn.computeIfAbsent(k.hsn(), x -> new HashSet<>()).addAll(nt);
    }
    for (String t : df.keySet()) vocabByLength.computeIfAbsent(t.length(), x -> new ArrayList<>()).add(t);
    for (GstRow.Hsn h : rows) {
      Set<String> s = new HashSet<>(tokens(h.productDescription() + " " + h.tariffDescription() + " " + h.conditionLabel()
          + " " + nz(h.nameGu())));
      s.addAll(namesByHsn.getOrDefault(h.hsn(), Set.of()));
      hsnTokens.add(s);
    }
  }

  /** Number of active HSN rows indexed. */
  public int hsnRowCount() { return hsnRows.size(); }

  /** Number of keyword rows indexed. */
  public int keywordCount() { return docs.size(); }

  /** The active rows of one HSN code in source order; empty when the code is unknown. */
  public List<GstRow.Hsn> rowsFor(String hsn) {
    return Collections.unmodifiableList(byCode.getOrDefault(hsn == null ? "" : hsn.strip(), List.of()));
  }

  /**
   * Best HSN and rate for a product name.
   *
   * @param name the product name as typed (English, Hindi or Gujarati spelling); pack sizes are ignored
   * @param category the product's category name, optional
   * @param price selling price or MRP in rupees, optional; decides price-band rows (clothing, footwear)
   * @param packed true = packed and labelled, false = loose, null = decide from the name, else packed
   * @param limit 1..10: the most hits returned in total (best + alternatives)
   */
  public Result suggest(String name, String category, BigDecimal price, Boolean packed, int limit) {
    String query = name == null ? "" : name.strip();
    Set<String> hints = new HashSet<>();
    List<String> q = queryTokens(query, hints);
    if (q.isEmpty() || docs.isEmpty()) return Result.none(query);
    Boolean wantPacked = packed;
    if (wantPacked == null) {
      boolean loose = hints.stream().anyMatch(LOOSE_WORDS::contains);
      boolean pack = hints.stream().anyMatch(PACKED_WORDS::contains);
      if (loose) wantPacked = Boolean.FALSE;
      else if (pack) wantPacked = Boolean.TRUE;
    }
    Set<String> cat = new LinkedHashSet<>(tokens(category == null ? "" : category));
    Set<String> qset = new LinkedHashSet<>(q);
    Set<String> core = new LinkedHashSet<>();
    for (String t : qset) if (!hints.contains(t)) core.add(t);
    if (core.isEmpty()) core.addAll(qset);
    String joined = " " + String.join(" ", q) + " ";

    Map<Integer, Double> score = new HashMap<>();
    Map<Integer, Integer> hits = new HashMap<>();
    for (String t : qset) {
      Map<Integer, Double> per = new HashMap<>();
      for (Weighted w : expand(t)) {
        int d = df.getOrDefault(w.token(), 1);
        for (int ri : index.getOrDefault(w.token(), List.of())) {
          double c = w.weight() * (1 + 2.0 / d) + (docs.get(ri).nameTokens().contains(w.token()) ? w.weight() : 0);
          if (c > per.getOrDefault(ri, 0.0)) per.put(ri, c);
        }
      }
      for (Map.Entry<Integer, Double> e : per.entrySet()) {
        score.merge(e.getKey(), e.getValue(), Double::sum);
        if (core.contains(t)) hits.merge(e.getKey(), 1, Integer::sum);
      }
    }
    if (score.isEmpty()) return Result.none(query);

    List<Scored> scored = new ArrayList<>();
    for (Map.Entry<Integer, Double> e : score.entrySet()) {
      int ri = e.getKey();
      Doc d = docs.get(ri);
      double s = e.getValue() * (0.5 + 0.5 * hits.getOrDefault(ri, 0) / (double) core.size());
      if (!d.namePhrase().isEmpty() && joined.contains(" " + d.namePhrase() + " ")) s += 4 + d.nameTokens().size();
      if (d.keywordPhrase().length() > 1 && joined.contains(" " + d.keywordPhrase() + " ")) s += 3;
      if (covers(d.nameTokens(), qset)) s += 2;
      if (covers(core, d.keywordTokens())) s += COVER_BONUS;
      if (!cat.isEmpty()) {
        for (String c : cat) {
          if (bestWeight(c, d.deptTokens()) >= 0.9) {
            s += 1.5;
            break;
          }
        }
      }
      if ("VERIFIED".equals(d.kw().verification())) s += VERIFIED_BONUS;
      scored.add(new Scored(ri, s));
    }
    scored.sort(Comparator.comparingDouble(Scored::raw).reversed().thenComparingInt(Scored::index));
    if (scored.isEmpty() || scored.get(0).raw() < CUT) return Result.none(query);

    double top = scored.get(0).raw();
    int max = Math.max(1, Math.min(limit, 10));
    List<Hit> found = new ArrayList<>();
    List<Hit> firstChoices = List.of();
    Set<String> seen = new HashSet<>();
    for (Scored s : scored) {
      if (s.raw() < CUT || s.raw() < top * ALT_SHARE) break;
      GstRow.Keyword k = docs.get(s.index()).kw();
      if (!seen.add(k.hsn())) continue;
      Resolved r = resolve(k.hsn(), k.conditionKey(), price, wantPacked);
      double conf = s.raw() / (s.raw() + CUT);
      if (found.isEmpty()) {
        List<Hit> ch = new ArrayList<>();
        for (GstRow.Hsn c : r.choices()) ch.add(hit(c, k, conf));
        firstChoices = ch;
      }
      found.add(hit(r.row(), k, conf));
      if (found.size() >= max) break;
    }
    return new Result(query, found.get(0), List.copyOf(firstChoices), List.copyOf(found.subList(1, found.size())));
  }

  private static Hit hit(GstRow.Hsn row, GstRow.Keyword k, double conf) {
    boolean sure = "VERIFIED".equals(row.verification()) && "VERIFIED".equals(k.verification());
    return new Hit(row, k.name(), conf, sure ? "VERIFIED" : "VERIFY_PRODUCT");
  }

  /** True when every word of {@code need} is in {@code have} (exact or plural, weight 0.9 or more). */
  private boolean covers(Set<String> need, Set<String> have) {
    for (String t : need) if (bestWeight(t, have) < 0.9) return false;
    return true;
  }

  /**
   * Picks the row(s) of one HSN code.
   *
   * @param cond the keyword's condition key: blank or ANY = automatic, PRICE = price bands, anything else forces that row
   */
  Resolved resolve(String hsn, String cond, BigDecimal price, Boolean packed) {
    List<GstRow.Hsn> rows = byCode.get(hsn);
    String c = cond == null ? "" : cond.strip();
    if (!c.isEmpty() && !c.equals("ANY") && !c.equals("PRICE")) {
      for (GstRow.Hsn r : rows) if (r.conditionKey().equals(c)) return new Resolved(r, List.of());
    }
    List<GstRow.Hsn> anys = new ArrayList<>();
    List<GstRow.Hsn> packLoose = new ArrayList<>();
    List<GstRow.Hsn> bands = new ArrayList<>();
    for (GstRow.Hsn r : rows) {
      if (r.conditionKey().equals("ANY")) anys.add(r);
      if (r.conditionKey().equals("PACKED_LABELLED") || r.conditionKey().equals("LOOSE")) packLoose.add(r);
      if (r.isPriceRow()) bands.add(r);
    }
    List<GstRow.Hsn> group;
    if (c.equals("PRICE") && !bands.isEmpty()) group = bands;
    else if (!anys.isEmpty()) return new Resolved(anys.get(0), List.of());
    else if (packLoose.size() > 1) group = packLoose;
    else if (bands.size() > 1) group = bands;
    else group = rows;

    List<GstRow.Hsn> ordered = new ArrayList<>(group);
    ordered.sort(Comparator.comparingInt(GstMatcher::rank));
    GstRow.Hsn pick = ordered.get(0);
    if (group == packLoose && packed != null) {
      String want = packed ? "PACKED_LABELLED" : "LOOSE";
      for (GstRow.Hsn r : ordered) {
        if (r.conditionKey().equals(want)) {
          pick = r;
          break;
        }
      }
    }
    if (price != null && price.signum() > 0 && group.stream().anyMatch(GstRow.Hsn::isPriceRow)) {
      for (GstRow.Hsn r : ordered) {
        if (r.isPriceRow() && r.matchesPrice(price)) {
          pick = r;
          break;
        }
      }
    }
    Set<BigDecimal> rates = new HashSet<>();
    for (GstRow.Hsn r : group) rates.add(r.gstPercent().stripTrailingZeros());
    if (rates.size() == 1) return new Resolved(pick, List.of());
    List<GstRow.Hsn> others = new ArrayList<>();
    for (GstRow.Hsn r : ordered) if (r != pick) others.add(r);
    return new Resolved(pick, others);
  }

  /** Packed / up-to-band first, loose / above-band next, everything else last. */
  private static int rank(GstRow.Hsn r) {
    String c = r.conditionKey();
    if (c.equals("PACKED_LABELLED") || c.startsWith("PRICE_UPTO")) return 0;
    if (c.equals("LOOSE") || c.startsWith("PRICE_ABOVE")) return 1;
    return 2;
  }

  /**
   * Ranked search over the HSN rows: a digits-only query is a code prefix (sorted by code); text is matched on the
   * product, heading and condition words and on the names of the keyword items of the same code, with the same synonyms
   * and spelling tolerance. Best first. A blank query returns every row in code order.
   */
  public List<GstRow.Hsn> search(String query, int limit) {
    String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
    List<GstRow.Hsn> out = new ArrayList<>();
    if (q.isEmpty()) {
      for (GstRow.Hsn h : hsnRows) {
        if (out.size() >= cap) break;
        out.add(h);
      }
      return out;
    }
    if (q.chars().allMatch(Character::isDigit)) {
      for (GstRow.Hsn h : hsnRows) {
        if (h.hsn().startsWith(q)) {
          out.add(h);
          if (out.size() >= cap) break;
        }
      }
      return out;
    }
    List<String> toks = tokens(q);
    if (toks.isEmpty()) return out;
    List<Scored> scored = new ArrayList<>();
    for (int i = 0; i < hsnRows.size(); i++) {
      double total = 0;
      boolean ok = true;
      for (String t : toks) {
        double w = bestWeight(t, hsnTokens.get(i));
        if (w <= 0) {
          ok = false;
          break;
        }
        total += w;
      }
      if (!ok) continue;
      GstRow.Hsn h = hsnRows.get(i);
      Set<String> own = new HashSet<>(tokens(h.productDescription() + " " + h.conditionLabel()));
      double bonus = 0;
      for (String t : toks) if (bestWeight(t, own) > 0) bonus += 0.5;
      if ("VERIFIED".equals(h.verification())) bonus += 0.2;
      scored.add(new Scored(i, total + bonus));
    }
    scored.sort(Comparator.comparingDouble(Scored::raw).reversed().thenComparingInt(Scored::index));
    for (Scored s : scored) {
      out.add(hsnRows.get(s.index()));
      if (out.size() >= cap) break;
    }
    return out;
  }

  /** Lower-case words of 2+ characters (any script), with spelling variants folded together. */
  static List<String> tokens(String s) {
    List<String> out = new ArrayList<>();
    if (s == null) return out;
    var m = WORD.matcher(s.toLowerCase(Locale.ROOT));
    while (m.find()) {
      String t = m.group();
      if (t.codePointCount(0, t.length()) >= 2) out.add(SYNONYMS.getOrDefault(t, t));
    }
    return out;
  }

  /** {@link #tokens} for a typed name: pack sizes and filler words dropped; loose / packed words also noted in {@code hints}. */
  static List<String> queryTokens(String s, Set<String> hints) {
    List<String> out = new ArrayList<>();
    for (String t : tokens(s)) {
      if (SIZE.matcher(t).matches() || NOISE.contains(t)) continue;
      if (LOOSE_WORDS.contains(t) || PACKED_WORDS.contains(t)) hints.add(t);
      out.add(t);
    }
    return out;
  }

  /**
   * The index words a typed word stands for: itself, simple plurals, else (when none exist) words it is a prefix of ("biscu" for
   * "biscuit"), else words one or two typos away. Cached per typed word.
   */
  private List<Weighted> expand(String t) {
    List<Weighted> cached = expandCache.get(t);
    if (cached != null) return cached;
    List<Weighted> out = new ArrayList<>();
    add(out, t, 1.0);
    if (t.length() > 3 && t.endsWith("s")) add(out, t.substring(0, t.length() - 1), 0.95);
    add(out, t + "s", 0.95);
    if (t.length() > 4 && t.endsWith("es")) add(out, t.substring(0, t.length() - 2), 0.9);
    if (out.isEmpty() && t.length() >= 3) {
      for (int len = t.length() + 1; len <= t.length() + 4; len++) {
        for (String v : vocabByLength.getOrDefault(len, List.of())) if (v.startsWith(t)) out.add(new Weighted(v, 0.65));
      }
    }
    if (out.isEmpty() && t.length() >= 4) {
      int max = t.length() >= 8 ? 2 : 1;
      for (int len = t.length() - max; len <= t.length() + max; len++) {
        for (String v : vocabByLength.getOrDefault(len, List.of())) {
          int d = distance(t, v, max);
          if (d > 0 && d <= max) out.add(new Weighted(v, d == 1 ? 0.7 : 0.55));
        }
      }
    }
    if (out.size() > 40) out = new ArrayList<>(out.subList(0, 40));
    List<Weighted> frozen = List.copyOf(out);
    if (expandCache.size() > 20_000) expandCache.clear();
    expandCache.put(t, frozen);
    return frozen;
  }

  private void add(List<Weighted> out, String v, double w) {
    if (!df.containsKey(v)) return;
    for (Weighted e : out) if (e.token().equals(v)) return;
    out.add(new Weighted(v, w));
  }

  /** Best weight of query word {@code t} against a set of row words. */
  private double bestWeight(String t, Set<String> row) {
    if (row.contains(t)) return 1;
    double best = 0;
    for (Weighted w : expand(t)) if (w.weight() > best && row.contains(w.token())) best = w.weight();
    return best;
  }

  /** Levenshtein distance with an early exit: returns {@code max + 1} as soon as it must exceed {@code max}. */
  static int distance(String a, String b, int max) {
    if (Math.abs(a.length() - b.length()) > max) return max + 1;
    int[] prev = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      int[] cur = new int[b.length() + 1];
      cur[0] = i;
      int rowMin = cur[0];
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        int v = Math.min(Math.min(prev[j - 1] + cost, prev[j] + 1), cur[j - 1] + 1);
        cur[j] = v;
        if (v < rowMin) rowMin = v;
      }
      if (rowMin > max) return max + 1;
      prev = cur;
    }
    return prev[b.length()];
  }

  private static String nz(String s) { return s == null ? "" : s; }
}
