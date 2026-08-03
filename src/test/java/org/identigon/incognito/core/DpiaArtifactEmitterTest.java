package org.identigon.incognito.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.identigon.incognito.api.AnonymisationReport;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.PipelineStage;
import org.identigon.incognito.api.SaltMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit coverage for the three DPIA output formats (no database needed). */
class DpiaArtifactEmitterTest {

    private static AnonymisationReport sampleReport() {
        var columns = List.of(
            new AnonymisationReport.ColumnAction("email", ColumnRole.DIRECT_ID, "ALTEREGO_EMAIL"),
            new AnonymisationReport.ColumnAction("flag", ColumnRole.SENSITIVE, "KEEP"),
            // A value with characters that must be escaped in JSON/HTML.
            new AnonymisationReport.ColumnAction("notes", ColumnRole.SENSITIVE, "say \"hi\" <b> & go"));
        var passthrough = List.of(
            new AnonymisationReport.PassthroughFlag("payload", "OTHER",
                "untransformed potentially-identifying type kept as-is (SPEC §7.2)"));
        var table = new AnonymisationReport.TableReport(
            "customers", columns, 42L, passthrough, List.of(), true);
        var survival = List.of(
            new AnonymisationReport.SurvivalFinding("customers", "email", 100L, 3L, false));
        var lint = List.of(
            new AnonymisationReport.LintFinding("customers", "city", 5000L, 50));
        var stages = List.of(
            new PipelineStage.StageResult("TableTransformLoadStage", true, 42, "loaded 42 rows"),
            new PipelineStage.StageResult("VerificationStage", false, 1, "Verification FAILED: dangling FK"));
        return new AnonymisationReport(
            SaltMode.EPHEMERAL, List.of(table), survival, lint, stages);
    }

    @Test
    void emitsJson(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("report.json");
        DpiaArtifactEmitter.emitJson(sampleReport(), out);
        String json = Files.readString(out);

        assertTrue(json.contains("\"table\": \"customers\""), "table name present");
        assertTrue(json.contains("\"rowsProcessed\": 42"), "row count present");
        assertTrue(json.contains("\"passthroughFlags\""), "passthrough section present");
        assertTrue(json.contains("\"success\": false"), "failed stage recorded");
        // Quotes in a value are escaped, not left raw.
        assertTrue(json.contains("say \\\"hi\\\""), "special characters JSON-escaped");
        assertTrue(json.contains("\"saltMode\": \"EPHEMERAL\""), "salt mode disclosed");
        assertTrue(json.contains("\"survivalFindings\""), "survival findings section present");
        assertTrue(json.contains("\"hardFailure\": false"), "survival verdict recorded");
        assertTrue(json.contains("\"lintFindings\""), "lint findings section present");
        assertTrue(json.contains("\"distinctValues\": 5000"), "lint distinct count recorded");
        assertEquals(count(json, '{'), count(json, '}'), "braces balanced");
    }

    @Test
    void emitsHtml(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("report.html");
        DpiaArtifactEmitter.emitHtml(sampleReport(), out);
        String html = Files.readString(out);

        assertTrue(html.startsWith("<!doctype html>"), "is an HTML document");
        assertTrue(html.contains("<code>customers</code>"), "table name present");
        assertTrue(html.contains(">FAILED<"), "failed stage flagged");
        // Angle brackets / ampersands in a value are escaped.
        assertTrue(html.contains("&lt;b&gt; &amp; go"), "special characters HTML-escaped");
        assertTrue(html.contains("Passthrough flags"), "passthrough section rendered");
        assertTrue(html.contains("Salt mode:"), "salt mode disclosed");
        assertTrue(html.contains("EPHEMERAL"), "salt mode value rendered");
        assertTrue(html.contains("Source-value survival"), "survival section rendered");
        assertTrue(html.contains("Misdeclaration lint"), "lint section rendered");
    }

    @Test
    void emitsMarkdown(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("report.md");
        DpiaArtifactEmitter.emitMarkdown(sampleReport(), out);
        String md = Files.readString(out);

        assertTrue(md.contains("# Incognito Anonymisation Report"), "has a title");
        assertTrue(md.contains("customers"), "table name present");
        assertTrue(md.contains("ALTEREGO_EMAIL"), "column action present");
        assertTrue(md.contains("**Salt mode:**"), "salt mode disclosed");
        assertTrue(md.contains("Source-Value Survival"), "survival section rendered");
        assertTrue(md.contains("Misdeclaration Lint"), "lint section rendered");
    }

    private static long count(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }
}
