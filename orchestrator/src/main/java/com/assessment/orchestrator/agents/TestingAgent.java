package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real testing stage: actually invokes {@code mvn test} against the url-shortener module and
 * parses the Surefire summary line. This is deliberately NOT simulated - a "testing agent" that
 * only prints a canned "tests passed" message would prove nothing, so this one shells out to the
 * real build and the stage genuinely fails if the real suite fails.
 */
public final class TestingAgent implements Agent {

    private static final Pattern SUMMARY = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) throws Exception {
        Path moduleDir = (Path) ctx.getConfig("urlShortenerModuleDir");
        if (moduleDir == null) {
            return StageOutcome.failure("urlShortenerModuleDir not configured - cannot locate the module to test");
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String mvnCommand = isWindows ? "mvn.cmd" : "mvn";

        ProcessBuilder pb = new ProcessBuilder(mvnCommand, "-f", moduleDir.resolve("pom.xml").toString(), "test");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitCode = process.waitFor();

        ArtifactWriter.write(ctx.getRunOutputDir(), "04-test-output.log", output.toString());

        // Surefire prints a "Tests run: ..." line per test class plus one final aggregate line
        // in the "Results:" section - only the last match is the real total, summing all of them
        // would double-count every test.
        Matcher matcher = SUMMARY.matcher(output);
        int totalTests = 0, failures = 0, errors = 0, skipped = 0;
        boolean matchedAny = false;
        while (matcher.find()) {
            matchedAny = true;
            totalTests = Integer.parseInt(matcher.group(1));
            failures = Integer.parseInt(matcher.group(2));
            errors = Integer.parseInt(matcher.group(3));
            skipped = Integer.parseInt(matcher.group(4));
        }

        boolean success = exitCode == 0;
        String summary = matchedAny
                ? String.format("mvn test exit=%d, tests=%d failures=%d errors=%d skipped=%d", exitCode, totalTests, failures, errors, skipped)
                : "mvn test exit=" + exitCode + " (no Surefire summary parsed - see 04-test-output.log)";

        ctx.recordDecision("TestingAgent", StageId.TESTING, success ? "TEST_SUITE_PASSED" : "TEST_SUITE_FAILED", summary);

        return StageOutcome.builder(summary)
                .success(success)
                .artifact("testExitCode", exitCode)
                .artifact("testsRun", totalTests)
                .artifact("testFailures", failures + errors)
                .build();
    }
}
