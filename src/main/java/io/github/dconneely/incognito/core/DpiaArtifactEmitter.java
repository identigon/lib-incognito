package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.AnonymisationReport;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.PipelineStage;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serializes the {@link AnonymisationReport} to a DPIA artifact. Three formats are offered
 * (SPEC §7 / PLAN Phase 6): machine-readable {@link #emitJson JSON}, presentation-ready
 * {@link #emitHtml HTML}, and human-diffable {@link #emitMarkdown Markdown}. All are zero-dependency
 * (no JSON/HTML library) so the core stays dependency-lean.
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
            w.write("{\n  \"stages\": [\n");
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
     * Emits the report as a Markdown file.
     * @param report the anonymisation report to emit
     * @param outputPath the path where the Markdown file will be written
     * @throws IncognitoException if writing fails
     */
    public static void emitMarkdown(AnonymisationReport report, Path outputPath) throws IncognitoException {
        try (Writer writer = Files.newBufferedWriter(outputPath)) {
            writer.write("# Incognito Anonymisation Report (DPIA Artifact)\n\n");

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
