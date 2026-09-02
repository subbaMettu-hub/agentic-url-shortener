package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Greenfield implementation: the QR-code endpoint is genuinely new surface area (no existing file
 * to patch). This agent verifies the required source files exist under the real url-shortener
 * module and snapshots them into the run's evidence folder - it is idempotent, so re-running the
 * scenario after the feature has already landed simply re-confirms it rather than duplicating work.
 */
public final class GreenfieldImplementationAgent implements Agent {

    private static final List<String> REQUIRED_FILES = List.of(
            "com/assessment/urlshortener/service/QrCodeService.java",
            "com/assessment/urlshortener/controller/QrCodeController.java"
    );

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) throws Exception {
        Path srcRoot = ctx.getRepoRoot();
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String rel : REQUIRED_FILES) {
            Path file = srcRoot.resolve(rel);
            if (Files.isRegularFile(file)) {
                present.add(rel);
                Path evidenceDir = ctx.getRunOutputDir().resolve("implementation");
                ArtifactWriter.write(evidenceDir, Path.of(rel).getFileName().toString(), Files.readString(file));
            } else {
                missing.add(rel);
            }
        }

        ctx.recordDecision("GreenfieldImplementationAgent", StageId.IMPLEMENTATION,
                missing.isEmpty() ? "IMPLEMENTATION_VERIFIED" : "IMPLEMENTATION_INCOMPLETE",
                missing.isEmpty()
                        ? "QR code feature files present and snapshotted as evidence: " + present
                        : "Missing required implementation file(s): " + missing);

        if (!missing.isEmpty()) {
            return StageOutcome.failure("Greenfield implementation incomplete, missing: " + missing);
        }

        return StageOutcome.builder("QR code generation feature implemented (" + present.size() + " file(s))")
                .artifact("implementationFiles", present)
                .build();
    }
}
