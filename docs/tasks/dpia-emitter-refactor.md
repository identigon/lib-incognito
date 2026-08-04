# Task: refactor DpiaArtifactEmitter (JsonWriter + text blocks)

Status: not started (build-time handoff; delete this file once implemented and green)

## 1. Goal

`src/main/java/org/identigon/incognito/core/DpiaArtifactEmitter.java` serialises an
`AnonymisationReport` to three formats by hand-concatenating strings. The JSON path is the fragile
one: it tracks commas with `(i == 0 ? "" : ", ")`, opens/closes braces by hand, and the unit test
resorts to asserting `count('{') == count('}')` because nothing else guarantees the braces balance.

This task removes that fragility **without adding any dependency** (the emitter's Javadoc promises
"zero-dependency … so the core stays dependency-lean" — keep that promise):

- **Part A — a tiny internal `JsonWriter`** that owns commas, braces, and string escaping, so the
  JSON can no longer be structurally malformed.
- **Part B / C — Java text blocks** for the HTML and Markdown scaffolding, so the page structure
  reads as one template instead of dozens of interleaved `w.write(...)` calls.

This is a **behaviour-preserving refactor**. It is not a redesign of the report and adds no new
public API, no new fields, and no new formats.

## 2. Hard constraints

1. **No new dependency.** Do not add Jackson/Gson/Freemarker/anything to `build.gradle`.
   `JsonWriter` is a package-private class in `org.identigon.incognito.core`, ~60 lines,
   `StringBuilder`-backed.
2. **The existing test is the safety net.**
   `src/test/java/org/identigon/incognito/core/DpiaArtifactEmitterTest.java` asserts substrings
   across all three formats. It must stay **green without weakening its assertions**. That means the
   JSON token style must stay `"key": value` (colon-space) and `, ` (comma-space) between items —
   see §3 — so assertions like `"saltMode": "EPHEMERAL"` and `"childColumns": ["customer_id"]` keep
   matching. HTML/Markdown assertions match on content substrings (`<code>customers</code>`,
   `Passthrough flags`, `Structural Re-identification Risk`, …) — keep those strings identical.
3. **Keep the three method signatures unchanged:** `public static void
   emitJson/emitHtml/emitMarkdown( AnonymisationReport report, Path outputPath) throws
   IncognitoException`. Callers (benchmarks, `BenchmarkSupport.emitAndVerifyDpiaReport`) must not
   change.
4. **Keep the existing helpers** `htmlEscape` and `saltModeNote` as-is. `jsonStr`'s escape logic
   moves *into* `JsonWriter` (it is the only caller after Part A) — do not duplicate it.

## 3. Part A — `JsonWriter`

Create `src/main/java/org/identigon/incognito/core/JsonWriter.java`. Reference implementation (copy
verbatim, then adjust Javadoc to the module's style — every non-public class still needs a class
comment for the doclint gate, but private methods do not):

```java
package org.identigon.incognito.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal, dependency-free JSON serialiser for the DPIA artifact. Owns comma placement, brace/bracket
 * nesting, and string escaping so the emitted JSON cannot be structurally malformed. Compact
 * single-line output with a readable {@code "key": value} / {@code , } token style. Not general
 * purpose — it supports exactly the shapes {@link DpiaArtifactEmitter} needs.
 */
final class JsonWriter {

    private final StringBuilder sb = new StringBuilder();
    // One frame per open object/array; frame[0] = "this container already has a child" (needs a comma).
    private final Deque<boolean[]> stack = new ArrayDeque<>();
    // True immediately after name(): the next value/beginObject/beginArray is that name's value and
    // must NOT be preceded by a comma.
    private boolean expectingValue = false;

    JsonWriter() { stack.push(new boolean[]{false}); } // synthetic root frame

    private void preItem() {
        if (expectingValue) { expectingValue = false; return; }
        boolean[] top = stack.peek();
        if (top[0]) sb.append(", "); else top[0] = true;
    }

    JsonWriter beginObject() { preItem(); sb.append('{'); stack.push(new boolean[]{false}); return this; }
    JsonWriter endObject()   { stack.pop(); sb.append('}'); return this; }
    JsonWriter beginArray()  { preItem(); sb.append('['); stack.push(new boolean[]{false}); return this; }
    JsonWriter endArray()    { stack.pop(); sb.append(']'); return this; }

    /** Writes {@code "key": } and expects a following value (object/array/scalar). */
    JsonWriter name(String key) { preItem(); sb.append(quote(key)).append(": "); expectingValue = true; return this; }

    JsonWriter field(String key, String value)  { name(key); sb.append(value == null ? "null" : quote(value)); expectingValue = false; return this; }
    JsonWriter field(String key, long value)     { name(key); sb.append(value); expectingValue = false; return this; }
    JsonWriter field(String key, boolean value)  { name(key); sb.append(value); expectingValue = false; return this; }

    /** A string element inside an array. */
    JsonWriter value(String v) { preItem(); sb.append(v == null ? "null" : quote(v)); return this; }

    String toJson() { return sb.toString(); }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
```

**Then rewrite `emitJson`** to build a `JsonWriter` and `Files.writeString(outputPath,
jw.toJson())`. Translate the current structure one-to-one; the mapping is mechanical:

- top level → `jw.beginObject()` … `jw.endObject()`.
- `"saltMode": <name-or-null>` → `jw.field("saltMode", report.saltMode() == null ? null :
  report.saltMode().name())`.
- each findings array → `jw.name("survivalFindings").beginArray(); for (var sf : …) {
  jw.beginObject().field("table", sf.table())….endObject(); } jw.endArray();`
- the `childColumns` string array → `jw.name("childColumns").beginArray(); for (var c :
  suf.childColumns()) jw.value(c); jw.endArray();`
- per-table objects, `columns`, `passthroughFlags`, `inferSuggestions`, `stages` → same pattern.

Delete the old private `jsonStr` method (now unused; `JsonWriter.quote` replaces it). Keep the
`try/catch (IOException)` → `IncognitoException` wrapping.

**Add `JsonWriterTest`** (`src/test/java/org/identigon/incognito/core/JsonWriterTest.java`, no DB):
empty object `{}`; a flat object with the `"k": v` and `, ` token style; a nested array of objects;
an empty array `[]`; escaping of `"`, `\`, newline, and a control char; a `null` string value. These
lock the writer's contract independently of the emitter.

**Verify:** `./gradlew test --tests "*JsonWriterTest" --tests "*DpiaArtifactEmitterTest"` green with
the emitter test's assertions unchanged. (Compact single-line JSON is expected and fine — JSON
whitespace is insignificant, and a governance system ingesting the file does not care. Do **not**
add pretty-print indentation just to match the old newlines; the test does not assert on them.)

## 4. Part B — HTML via text blocks

In `emitHtml`, lift the **static** scaffolding into `private static final String` text blocks and
render the **dynamic** parts with `String.format`/`"…".formatted(...)`:

- The document head + CSS (`<!doctype …>` through `</style>\n</head><body>…`) → one text-block
  constant.
- Each table's caption + header row (`<table><caption>…</caption><tr><th>…</th>…</tr>`) → a
  constant.
- Row loops stay (a list is variable-length), but each row body becomes a single `formatted()` call,
  e.g. `ROW_FMT.formatted(htmlEscape(sf.table()), htmlEscape(sf.column()), sf.sampledDistinct(),
  …)`.
- Keep every user-facing substring the HTML test asserts on **identical**. Keep `htmlEscape` on
  every interpolated value exactly where it is today (escaping is a correctness property, not
  cosmetics).

**Gotcha:** `String.format`/`formatted` treats `%` as a format specifier. The current CSS/markup has
no literal `%`, so this is safe — but if you ever interpolate content containing `%`, format it as a
`%s` argument, never inline it into the template string.

## 5. Part C — Markdown via text blocks

Same treatment in `emitMarkdown`: text-block constants for the section headers and the `|---|`
separator rows; one `formatted()` per data row. Preserve the exact heading strings the Markdown test
asserts (`Source-Value Survival`, `Misdeclaration Lint`, `Structural Re-identification Risk`,
`Inference Suggestions`, …).

**Gotcha:** the current code mixes explicit `\n` with `String.format`'s `%n` (platform newline).
Pick `\n` throughout for determinism (text blocks use `\n`); the tests use `contains(...)` so
line-ending style does not affect them, but consistency avoids `\r\n` creeping into committed golden
output.

## 6. Sequencing, verification, and definition of done

Do the parts **in order, one at a time**, running the emitter test after each so a regression is
isolated to the part you just touched:

1. Part A (`JsonWriter` + `emitJson` + `JsonWriterTest`) — highest value, do first.
2. Part B (HTML).
3. Part C (Markdown).

Extra verification technique (recommended): before starting, run `./gradlew test --tests
"*BenchmarkE2ETest"` once and copy a generated `build/dpia-reports/<name>/report.{json,html,md}`
aside. After each part, regenerate and `diff` — the only expected differences are JSON whitespace
(Part A) and none at all for HTML/Markdown content.

**Done when:** `./gradlew build` is green (compile, all tests, and the strict `Xdoclint:all` javadoc
gate — `JsonWriter` needs its class comment); `DpiaArtifactEmitterTest` passes with unchanged
assertions; `build.gradle` has no new dependency; `jsonStr` is gone; and `htmlEscape`/`saltModeNote`
are untouched.

## 7. Follow-on task — 3 illustrative sample rows per table (do AFTER §§3–6)

Add a small **"Sample rows (illustrative)"** table under each table's report, showing **3 synthetic
rows** so the reader can see at a glance what the data looks like *after* transformation —
fabricated columns show a generated example value; kept/link/inherited columns show a placeholder.

Two properties make this safe and low-maintenance, and both must be preserved:

- **Generated, never hand-authored.** Each fabricated cell is produced by calling AlterEgo's real
  public methods, so the illustrated format can never silently drift from what the library actually
  produces. The only parallel code is a small `strategy → which method` switch, which the Java
  compiler forces to stay exhaustive over each enum.
- **Synthetic, never real data.** Cells are generated from fixed dummy seeds with a **fixed,
  non-secret example salt** — unrelated to the run's real salt (which is destroyed before the
  emitter runs anyway). The rows correspond to no real subject, so there is no co-occurrence and no
  disclosure question. Caption every such table *illustrative — synthetic data, not this run's
  rows*.

Do this only after the §§3–6 refactor is merged and green. Steps, in order:

**Step 1 — record + schema.** Add `java.util.List<String> examples` as the last component of
`AnonymisationReport.ColumnAction` (`api` package) — the 3 sample values for that column, in row
order — with a Javadoc `@param` (the doclint gate requires it). Update the `ColumnAction` line in
the SPEC §7 schema block to match. The compiler then points you at the two call sites to fix:
`AnonymisationReportBuilder` (Step 2) and `DpiaArtifactEmitterTest` (Step 4). (Storing the samples
per column keeps generation simple; the emitters transpose columns×samples into rows in Step 3.)

**Step 2 — generate them in `AnonymisationReportBuilder`.** Add the two helpers below. Build **one**
throwaway AlterEgo for the whole report by wrapping the existing table-building loop in `try (var ex
= exampleAlterEgo()) { … }` (it is `AutoCloseable`; this zeroes the example salt on exit). Where
each `ColumnAction` is constructed, also build its samples and pass them in:

```java
List<String> examples = new java.util.ArrayList<>();
for (int i = 0; i < SEEDS.size(); i++) examples.add(exampleCell(ex, colPol, SEEDS.get(i), i));
columnActions.add(new AnonymisationReport.ColumnAction(colName, colPol.role(), transformation, examples));
```

The helpers (copy verbatim; adjust imports to the file's style). `exampleCell` **mirrors the branch
order of the existing `transformation` switch** so the two never diverge:

```java
// A fixed, NON-SECRET salt used only for illustrative examples. It protects nothing and is unrelated
// to the run's real salt, so examples are deterministic, reproducible, and have zero linkage to any
// real or run data.
private static final byte[] EXAMPLE_SALT =
    "incognito-illustrative-examples".getBytes(java.nio.charset.StandardCharsets.UTF_8);
private static final java.util.List<String> SEEDS = java.util.List.of("sample-a", "sample-b", "sample-c");

/** A throwaway AlterEgo for illustrative examples only; caller must close it. Locale is fixed to the
 *  library default — examples are illustrations, so the run's exact locale is not needed. */
private static org.identigon.alterego.AlterEgo exampleAlterEgo() {
    return org.identigon.alterego.AlterEgo.builder()
        .salt(EXAMPLE_SALT.clone())
        .locale(java.util.Locale.UK)
        .rawMappingKeys(false)
        .mappingStore(new org.identigon.alterego.store.InMemoryMappingStore())
        .build();
}

/** One illustrative cell for a column at row `i`. Never touches real data. Any generation failure
 *  degrades to a placeholder rather than breaking the report. */
private static String exampleCell(org.identigon.alterego.AlterEgo ex,
        org.identigon.incognito.policy.ColumnPolicy colPol, String seed, int i) {
    try {
        ColumnRole role = colPol.role();
        if (role == ColumnRole.PAYLOAD) return "‹kept›";
        if (role == ColumnRole.FOREIGN_KEY) return "‹link›";
        if (role == ColumnRole.INHERITED_ATTRIBUTE) return "‹inherited›";
        if (role == ColumnRole.SENSITIVE) {
            if (Boolean.FALSE.equals(colPol.distinguishing())) return "‹kept›";
            if (colPol.redactionStrategy() != null) return switch (colPol.redactionStrategy()) {
                case MASK -> ex.mask('*', 0).apply(seed);
                case CLEAR -> "(cleared)";
                case CONSTANT -> "(fixed value)";
            };
            if (colPol.quasiIdStrategy() != null) return shiftedDate(ex, i);
            return "(redacted)";
        }
        if (role == ColumnRole.PRIMARY_KEY) {
            if (colPol.surrogateStrategy() == SurrogateStrategy.UUID_V4)
                return java.util.UUID.nameUUIDFromBytes(
                    seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            if (colPol.surrogateStrategy() == SurrogateStrategy.PASSTHROUGH_SURROGATE) return "‹kept›";
            return String.valueOf(1001 + i); // SEQUENTIAL_LONG or null
        }
        if (role == ColumnRole.QUASI_ID) return shiftedDate(ex, i);
        if (role == ColumnRole.DIRECT_ID || role == ColumnRole.UNIQUE_CANDIDATE_KEY) {
            DirectIdStrategy s = colPol.directIdStrategy();
            if (s == null) return "Example-" + seed;
            return switch (s) {
                case ALTEREGO_NAME -> ex.fullName().apply(seed);
                case ALTEREGO_FIRST_NAME -> ex.firstName().apply(seed);
                case ALTEREGO_LAST_NAME -> ex.lastName().apply(seed);
                case ALTEREGO_ORGANISATION -> ex.organisationName().apply(seed);
                case ALTEREGO_CITY -> ex.city().apply(seed);
                case ALTEREGO_STREET_ADDRESS -> ex.streetAddress().apply(seed);
                case ALTEREGO_POSTCODE -> ex.postcode().apply(seed);
                case ALTEREGO_EMAIL -> ex.emailAddress().apply(seed);
                case ALTEREGO_PHONE -> ex.phoneNumber().apply(seed);
                case ALTEREGO_DOMAIN -> ex.domainName().apply(seed);
                case ALTEREGO_URL -> ex.url().apply(seed);
                case ALTEREGO_GENERIC -> "Example-" + seed;
            };
        }
        return "‹kept›";
    } catch (RuntimeException e) {
        return "‹example unavailable›";
    }
}

private static String shiftedDate(org.identigon.alterego.AlterEgo ex, int i) {
    return ex.shiftDate(org.identigon.alterego.AlterEgo.DateField.MONTH)
             .apply(java.time.LocalDate.of(1984, 1 + i, 15)).toString();
}
```

Use the literal guillemets `‹ ›` for placeholders **deliberately**: they contain no `&`, `<`, or
`>`, so `htmlEscape` leaves them intact — do not switch to `<kept>`, which would render as
`&lt;kept&gt;`. The method names above are exactly those `TableTransformLoadStage` calls (see its
DIRECT_ID switch), so if AlterEgo renames one, both fail to compile and get fixed together — that is
the anti-drift property; keep the two in step.

**Step 3 — render the rows in all three formats** (reuse the cleaned-up emitters from §§3–6). The
samples are stored per column, so HTML and Markdown transpose them into rows; JSON just carries them
per column. Render only when the table has columns and non-empty `examples`.

- **JSON** (`emitJson`, per-table `columns` objects): after `transformation`, add an array
  `jw.name("examples").beginArray(); for (var e : ca.examples()) jw.value(e); jw.endArray();`.
- **HTML** (`emitHtml`, after the existing "Column actions" table for the table): emit a new
  `<table>` with `<caption>Sample rows (illustrative — synthetic data, not this run's
  rows)</caption>`, a header row of `<th>htmlEscape(ca.column())</th>` for each column, then for `i`
  in `0..2` a `<tr>` of `<td>htmlEscape(ca.examples().get(i))</td>` for each column.
- **Markdown** (`emitMarkdown`, after the "Column Actions" table): a `#### Sample Rows
  (Illustrative)` heading with an italic note `*Synthetic data showing each column's transformation,
  not this run's rows.*`, then a table whose header is the column names, a `|---|` separator with
  one cell per column, and 3 rows built the same way (`ca.examples().get(i)` per column).

**Step 4 — tests + DoD.** Update `DpiaArtifactEmitterTest.sampleReport()`'s three `ColumnAction`
constructions to pass a 3-element list (e.g. `List.of("a@example.com", "b@example.com",
"c@example.com")`, `List.of("‹kept›", "‹kept›", "‹kept›")`, …); add one assertion per format that
the "Sample rows"/"Sample Rows" table (or JSON `"examples"`) renders. Existing assertions keep
passing (a new table/field removes no existing substring). `./gradlew build` green, including the
javadoc gate.

**Scope note:** examples use a fixed locale (`Locale.UK`, the library default) purely for
simplicity; threading the run's actual locale into the throwaway AlterEgo is a later refinement, not
needed for a first cut.
