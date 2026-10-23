package de.cgoit.faktura.archive;

import java.util.List;

/**
 * Outcome of an export run.
 *
 * @param exported       copies written in this run
 * @param alreadyPresent files found in the export directory with the expected content, only remembered
 * @param problems       file names that could not be exported (name only, never content)
 */
public record ExportResult(int exported, int alreadyPresent, List<String> problems) {

    public boolean ok() {
        return problems.isEmpty();
    }
}
