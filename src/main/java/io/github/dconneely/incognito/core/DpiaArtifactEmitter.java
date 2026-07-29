package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.AnonymisationReport;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.PipelineStage;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serializes the AnonymisationReport to a DPIA artifact (Markdown format).
 */
public final class DpiaArtifactEmitter {

    private DpiaArtifactEmitter() {}

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
