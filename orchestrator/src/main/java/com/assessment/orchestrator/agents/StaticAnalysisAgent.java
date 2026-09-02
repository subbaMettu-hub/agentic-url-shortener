package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Simulated lint/security scan, but over the real url-shortener source tree - findings are real
 * text matches, not fabricated prose. Reports findings; whether a finding is BLOCK or WARN
 * severity is decided centrally by the {@link PolicyEngine}, not by this agent.
 *
 * Scenarios can configure a bounded number of simulated transient tool failures via the
 * "staticAnalysisFlakyAttempts" run config key, to exercise the retry/backoff path against a
 * realistic failure mode (a scanner service timing out) without faking the actual analysis.
 */
public final class StaticAnalysisAgent implements Agent {

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) {
        Integer flakyAttempts = (Integer) ctx.getConfig("staticAnalysisFlakyAttempts");
        if (flakyAttempts != null && attemptNumber <= flakyAttempts) {
            return StageOutcome.failure("Static analysis tool timed out contacting its rule database "
                    + "(simulated transient failure, attempt " + attemptNumber + "/" + flakyAttempts + ")");
        }

        List<String> findings = new ArrayList<>();
        Path srcRoot = ctx.getRepoRoot();
        if (srcRoot != null && Files.isDirectory(srcRoot)) {
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        String rel = srcRoot.relativize(p).toString().replace('\\', '/');
                        if (content.contains("password=") || content.contains("apiKey=\"")) {
                            findings.add("SECRET_PATTERN in " + rel);
                        }
                        if (content.contains("javascript:") && !rel.contains("test")) {
                            findings.add("UNSAFE_SCHEME_REFERENCE in " + rel);
                        }
                    } catch (IOException ignored) {
                        // best-effort scan
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String report = "# Static Analysis Report\n\n"
                + "Files scanned under: " + srcRoot + "\n\n"
                + "Findings: " + findings.size() + "\n\n"
                + (findings.isEmpty() ? "No blocking patterns detected.\n"
                        : findings.stream().map(f -> "- " + f + "\n").reduce("", String::concat));
        ArtifactWriter.write(ctx.getRunOutputDir(), "03-static-analysis.md", report);

        ctx.recordDecision("StaticAnalysisAgent", StageId.STATIC_ANALYSIS, "SCAN_COMPLETE",
                findings.isEmpty() ? "No blocking findings." : findings.size() + " finding(s) reported to policy engine.");

        return StageOutcome.builder("Static analysis complete: " + findings.size() + " finding(s)")
                .artifact("staticAnalysisFindings", findings)
                .build();
    }
}
