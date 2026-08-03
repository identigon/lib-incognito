package org.identigon.incognito.core;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.identigon.incognito.api.AnonymisationReport;
import org.identigon.incognito.api.IncognitoException;
import org.identigon.incognito.api.PipelineStage;

/**
 * Serializes the {@link AnonymisationReport} to a DPIA artifact. Three formats are offered
 * (SPEC §7 / PLAN Phase 6): machine-readable {@link #emitJson JSON}, presentation-ready
 * {@link #emitHtml HTML}, and human-diffable {@link #emitMarkdown Markdown}. All are zero-dependency
 * (no JSON/HTML library) so the core stays dependency-lean.
 *
 * <p>This is <b>opt-in</b>: the pipeline always builds the {@link AnonymisationReport} (available from
 * {@code PipelineResult.report()}), but it never writes a file automatically. A caller that wants a
 * persisted DPIA artifact invokes one of these methods with that report — e.g.
 * {@code DpiaArtifactEmitter.emitJson(result.report(), path)}.
 */
public final class DpiaArtifactEmitter {

    private DpiaArtifactEmitter() {}

    // --- JSON ---------------------------------------------------------------------------------

    /**
     * Emits the report as a JSON document — the machine-readable DPIA artifact for ingestion into a
     * governance system.
     * @param report the anonymisation report to emit
     * @param outputPath the path where the JSON file will be written
     * @throws IncognitoException if writing fails
     */
    public static void emitJson(AnonymisationReport report, Path outputPath) throws IncognitoException {
        try (Writer w = Files.newBufferedWriter(outputPath)) {
            w.write("{\n  \"saltMode\": "
                + jsonStr(report.saltMode() == null ? null : report.saltMode().name()) + ",\n");

            w.write("  \"survivalFindings\": [");
            for (int s = 0; s < report.survivalFindings().size(); s++) {
                AnonymisationReport.SurvivalFinding sf = report.survivalFindings().get(s);
                w.write((s == 0 ? "" : ", ") + "{\"table\": " + jsonStr(sf.table())
                    + ", \"column\": " + jsonStr(sf.column())
                    + ", \"sampledDistinct\": " + sf.sampledDistinct()
                    + ", \"survived\": " + sf.survived()
                    + ", \"hardFailure\": " + sf.hardFailure() + "}");
            }
            w.write("],\n  \"lintFindings\": [");
            for (int l = 0; l < report.lintFindings().size(); l++) {
                AnonymisationReport.LintFinding lf = report.lintFindings().get(l);
                w.write((l == 0 ? "" : ", ") + "{\"table\": " + jsonStr(lf.table())
                    + ", \"column\": " + jsonStr(lf.column())
                    + ", \"distinctValues\": " + lf.distinctValues()
                    + ", \"threshold\": " + lf.threshold() + "}");
            }
            w.write("],\n  \"stages\": [\n");
            for (int i = 0; i < report.stageResults().size(); i++) {
                PipelineStage.StageResult sr = report.stageResults().get(i);
                w.write("    {\"stage\": " + jsonStr(sr.stageName())
                    + ", \"success\": " + sr.success()
                    + ", \"processed\": " + sr.processedCount()
                    + ", \"message\": " + jsonStr(sr.message()) + "}"
                    + (i < report.stageResults().size() - 1 ? "," : "") + "\n");
            }
            w.write("  ],\n  \"tables\": [\n");
            for (int t = 0; t < report.tables().size(); t++) {
                AnonymisationReport.TableReport tr = report.tables().get(t);
                w.write("    {\n      \"table\": " + jsonStr(tr.table())
                    + ",\n      \"rowsProcessed\": " + tr.rowsProcessed()
                    + ",\n      \"fictionalityVerified\": " + tr.fictionalityVerified()
                    + ",\n      \"columns\": [");
                for (int c = 0; c < tr.columns().size(); c++) {
                    AnonymisationReport.ColumnAction ca = tr.columns().get(c);
                    w.write((c == 0 ? "" : ", ") + "{\"column\": " + jsonStr(ca.column())
                        + ", \"role\": " + jsonStr(ca.role().name())
                        + ", \"transformation\": " + jsonStr(ca.transformation()) + "}");
                }
                w.write("],\n      \"passthroughFlags\": [");
                for (int p = 0; p < tr.passthroughFlags().size(); p++) {
                    AnonymisationReport.PassthroughFlag pf = tr.passthroughFlags().get(p);
                    w.write((p == 0 ? "" : ", ") + "{\"column\": " + jsonStr(pf.column())
                        + ", \"jdbcType\": " + jsonStr(pf.jdbcType())
                        + ", \"reason\": " + jsonStr(pf.reason()) + "}");
                }
                w.write("]\n    }" + (t < report.tables().size() - 1 ? "," : "") + "\n");
            }
            w.write("  ]\n}\n");
        } catch (IOException e) {
            throw new IncognitoException("Failed to write DPIA JSON report to " + outputPath, e);
        }
    }

    // --- HTML ---------------------------------------------------------------------------------

    /**
     * Emits the report as a self-contained HTML document — the presentation-ready DPIA artifact.
     * @param report the anonymisation report to emit
     * @param outputPath the path where the HTML file will be written
     * @throws IncognitoException if writing fails
     */
    public static void emitHtml(AnonymisationReport report, Path outputPath) throws IncognitoException {
        try (Writer w = Files.newBufferedWriter(outputPath)) {
            w.write("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
            w.write("<title>Incognito Anonymisation Report (DPIA Artifact)</title>\n");
            w.write("<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:70rem}"
                + "table{border-collapse:collapse;margin:.5rem 0 1.5rem}"
                + "th,td{border:1px solid #ccc;padding:.3rem .6rem;text-align:left}"
                + "th{background:#f2f2f2}.fail{color:#b00}.ok{color:#080}"
                + "caption{font-weight:bold;text-align:left;padding:.3rem 0}</style>\n");
            w.write("</head><body>\n<h1>Incognito Anonymisation Report (DPIA Artifact)</h1>\n");

            w.write("<p><b>Salt mode:</b> <code>"
                + htmlEscape(report.saltMode() == null ? "unknown" : report.saltMode().name())
                + "</code> &mdash; " + saltModeNote(report.saltMode()) + "</p>\n");

            w.write("<h2>Residual re-identification risk</h2>\n");
            if (report.survivalFindings().isEmpty() && report.lintFindings().isEmpty()) {
                w.write("<p class=\"ok\">No source-value survival or misdeclaration findings.</p>\n");
            }
            if (!report.survivalFindings().isEmpty()) {
                w.write("<table><caption>Source-value survival (SPEC &sect;4.3 &mdash; singling-out evidence)"
                    + "</caption><tr><th>Table</th><th>Column</th><th>Sampled</th><th>Survived</th>"
                    + "<th>Verdict</th></tr>\n");
                for (AnonymisationReport.SurvivalFinding sf : report.survivalFindings()) {
                    w.write("<tr><td>" + htmlEscape(sf.table()) + "</td><td>" + htmlEscape(sf.column())
                        + "</td><td>" + sf.sampledDistinct() + "</td><td>" + sf.survived()
                        + "</td><td class=\"" + (sf.hardFailure() ? "fail\">LEAK" : "ok\">coincidental")
                        + "</td></tr>\n");
                }
                w.write("</table>\n");
            }
            if (!report.lintFindings().isEmpty()) {
                w.write("<table><caption>Misdeclaration lint (SPEC &sect;4.1 &mdash; distinguishing:false"
                    + " kept opaque)</caption><tr><th>Table</th><th>Column</th><th>Distinct values</th>"
                    + "<th>Threshold</th></tr>\n");
                for (AnonymisationReport.LintFinding lf : report.lintFindings()) {
                    w.write("<tr><td>" + htmlEscape(lf.table()) + "</td><td>" + htmlEscape(lf.column())
                        + "</td><td>" + lf.distinctValues() + "</td><td>" + lf.threshold() + "</td></tr>\n");
                }
                w.write("</table>\n");
            }

            w.write("<h2>Pipeline stages</h2>\n<table><tr><th>Stage</th><th>Result</th>"
                + "<th>Processed</th><th>Message</th></tr>\n");
            for (PipelineStage.StageResult sr : report.stageResults()) {
                w.write("<tr><td>" + htmlEscape(sr.stageName()) + "</td><td class=\""
                    + (sr.success() ? "ok\">OK" : "fail\">FAILED") + "</td><td>" + sr.processedCount()
                    + "</td><td>" + htmlEscape(sr.message()) + "</td></tr>\n");
            }
            w.write("</table>\n<h2>Tables</h2>\n");
            if (report.tables().isEmpty()) w.write("<p>No tables processed.</p>\n");

            for (AnonymisationReport.TableReport tr : report.tables()) {
                w.write("<h3><code>" + htmlEscape(tr.table()) + "</code></h3>\n"
                    + "<p>Rows processed: " + tr.rowsProcessed() + " &middot; Fictionality verified: "
                    + tr.fictionalityVerified() + "</p>\n");
                w.write("<table><caption>Column actions</caption><tr><th>Column</th><th>Role</th>"
                    + "<th>Transformation</th></tr>\n");
                for (AnonymisationReport.ColumnAction ca : tr.columns()) {
                    w.write("<tr><td>" + htmlEscape(ca.column()) + "</td><td>" + ca.role()
                        + "</td><td>" + htmlEscape(ca.transformation()) + "</td></tr>\n");
                }
                w.write("</table>\n");
                if (!tr.passthroughFlags().isEmpty()) {
                    w.write("<table><caption>Passthrough flags (opaque types kept as-is)</caption>"
                        + "<tr><th>Column</th><th>JDBC type</th><th>Reason</th></tr>\n");
                    for (AnonymisationReport.PassthroughFlag pf : tr.passthroughFlags()) {
                        w.write("<tr><td>" + htmlEscape(pf.column()) + "</td><td>" + htmlEscape(pf.jdbcType())
                            + "</td><td>" + htmlEscape(pf.reason()) + "</td></tr>\n");
                    }
                    w.write("</table>\n");
                }
            }
            w.write("</body></html>\n");
        } catch (IOException e) {
            throw new IncognitoException("Failed to write DPIA HTML report to " + outputPath, e);
        }
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A one-line plain-language gloss of a salt mode's anonymity implication (SPEC §5.1/§5.2), for
     * the DPIA reader who should not have to know the API to understand the run's re-identification
     * posture. Contains no markup, so it is safe in both HTML and Markdown output.
     */
    private static String saltModeNote(org.identigon.incognito.api.SaltMode mode) {
        if (mode == null) return "salt mode not recorded";
        return switch (mode) {
            case EPHEMERAL -> "fresh per-run salt, destroyed on completion; output unlinkable and irreversible";
            case PERSISTENT -> "fixed reused salt; output is linkable across runs and forfeits irreversibility (SPEC §5.2)";
            case REPRODUCIBLE -> "fixed salt + seed for reproducible fixtures; linkable and not for production clones (SPEC §5.2)";
        };
    }

    /**
     * Emits the report as a Markdown file.
     * @param report the anonymisation report to emit
     * @param outputPath the path where the Markdown file will be written
     * @throws IncognitoException if writing fails
     */
    public static void emitMarkdown(AnonymisationReport report, Path outputPath) throws IncognitoException {
        try (Writer writer = Files.newBufferedWriter(outputPath)) {
            writer.write("# Incognito Anonymisation Report (DPIA Artifact)\n\n");

            writer.write(String.format("**Salt mode:** `%s` — %s%n%n",
                report.saltMode() == null ? "unknown" : report.saltMode().name(),
                saltModeNote(report.saltMode())));

            writer.write("## Residual Re-identification Risk\n\n");
            if (report.survivalFindings().isEmpty() && report.lintFindings().isEmpty()) {
                writer.write("No source-value survival or misdeclaration findings.\n\n");
            }
            if (!report.survivalFindings().isEmpty()) {
                writer.write("### Source-Value Survival (SPEC §4.3 — singling-out evidence)\n\n");
                writer.write("| Table | Column | Sampled | Survived | Verdict |\n");
                writer.write("|---|---|---|---|---|\n");
                for (AnonymisationReport.SurvivalFinding sf : report.survivalFindings()) {
                    writer.write(String.format("| %s | %s | %d | %d | %s |%n",
                        sf.table(), sf.column(), sf.sampledDistinct(), sf.survived(),
                        sf.hardFailure() ? "LEAK" : "coincidental"));
                }
                writer.write("\n");
            }
            if (!report.lintFindings().isEmpty()) {
                writer.write("### Misdeclaration Lint (SPEC §4.1 — distinguishing:false kept opaque)\n\n");
                writer.write("| Table | Column | Distinct Values | Threshold |\n");
                writer.write("|---|---|---|---|\n");
                for (AnonymisationReport.LintFinding lf : report.lintFindings()) {
                    writer.write(String.format("| %s | %s | %d | %d |%n",
                        lf.table(), lf.column(), lf.distinctValues(), lf.threshold()));
                }
                writer.write("\n");
            }

            writer.write("## Pipeline Stages Summary\n\n");
            for (PipelineStage.StageResult sr : report.stageResults()) {
                writer.write(String.format("- **%s**: %s (Processed: %d, Success: %b)\n",
                    sr.stageName(), sr.message(), sr.processedCount(), sr.success()));
            }
            writer.write("\n");

            writer.write("## Table Reports\n\n");
            if (report.tables().isEmpty()) {
                writer.write("No tables processed.\n");
            }

            for (AnonymisationReport.TableReport tr : report.tables()) {
                writer.write(String.format("### Table: `%s`\n\n", tr.table()));
                writer.write(String.format("- Rows Processed: %d\n", tr.rowsProcessed()));
                writer.write(String.format("- Fictionality Verified: %b\n\n", tr.fictionalityVerified()));

                writer.write("#### Column Actions\n\n");
                writer.write("| Column | Role | Transformation |\n");
                writer.write("|---|---|---|\n");
                for (AnonymisationReport.ColumnAction ca : tr.columns()) {
                    writer.write(String.format("| %s | %s | %s |\n", ca.column(), ca.role(), ca.transformation()));
                }
                writer.write("\n");

                if (!tr.inferSuggestions().isEmpty()) {
                    writer.write("#### Inference Suggestions (Not Auto-Applied)\n\n");
                    writer.write("| Column | Suggested Role | Heuristic |\n");
                    writer.write("|---|---|---|\n");
                    for (AnonymisationReport.InferSuggestion is : tr.inferSuggestions()) {
                        writer.write(String.format("| %s | %s | %s |\n", is.column(), is.suggestedRole(), is.matchedHeuristic()));
                    }
                    writer.write("\n");
                }

                if (!tr.passthroughFlags().isEmpty()) {
                    writer.write("#### Passthrough Flags (Opaque Data Types)\n\n");
                    writer.write("| Column | JDBC Type | Reason |\n");
                    writer.write("|---|---|---|\n");
                    for (AnonymisationReport.PassthroughFlag pf : tr.passthroughFlags()) {
                        writer.write(String.format("| %s | %s | %s |\n", pf.column(), pf.jdbcType(), pf.reason()));
                    }
                    writer.write("\n");
                }
            }

        } catch (IOException e) {
            throw new IncognitoException("Failed to write DPIA report to " + outputPath, e);
        }
    }
}
