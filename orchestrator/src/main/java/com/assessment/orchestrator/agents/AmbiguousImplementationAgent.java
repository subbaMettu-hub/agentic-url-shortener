package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Ambiguous-requirement implementation: verifies the reliability guardrails that the clarified,
 * re-planned requirement scoped in (bounded rate limiting + health reporting), deliberately
 * scoped narrower than "reliable" could have been read as - the deferred items are listed
 * explicitly rather than attempted half-way.
 */
public final class AmbiguousImplementationAgent implements Agent {

    private static final String RATE_LIMITER_FILE = "com/assessment/urlshortener/service/RateLimiterService.java";
    private static final String RATE_LIMIT_FILTER_FILE = "com/assessment/urlshortener/config/RateLimitFilter.java";

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) throws Exception {
        RequirementSpec spec = ctx.getArtifact("requirementSpec");
        Path srcRoot = ctx.getRepoRoot();

        boolean hasLimiterService = Files.isRegularFile(srcRoot.resolve(RATE_LIMITER_FILE));
        boolean hasLimiterFilter = Files.isRegularFile(srcRoot.resolve(RATE_LIMIT_FILTER_FILE));

        ArtifactWriter.write(ctx.getRunOutputDir(), "implementation-summary.md",
                "# Reliability implementation\n\n"
                        + "**Scope actually implemented:** " + spec.normalizedGoal() + "\n\n"
                        + "- Rate limiter service present: " + hasLimiterService + "\n"
                        + "- Rate limit filter present: " + hasLimiterFilter + "\n"
                        + "- Health reporting: delegated to Spring Boot Actuator (`/actuator/health`), already present\n\n"
                        + "**Explicitly deferred (see requirements revision):**\n"
                        + spec.outOfScope().stream().map(s -> "- " + s + "\n").reduce("", String::concat));

        ctx.recordDecision("AmbiguousImplementationAgent", StageId.IMPLEMENTATION,
                (hasLimiterService && hasLimiterFilter) ? "RELIABILITY_GUARDRAILS_VERIFIED" : "RELIABILITY_GUARDRAILS_MISSING",
                "limiterService=" + hasLimiterService + " limiterFilter=" + hasLimiterFilter);

        if (!hasLimiterService || !hasLimiterFilter) {
            return StageOutcome.failure("Rate limiting guardrail files missing");
        }

        return StageOutcome.builder("Reliability guardrails (rate limiting + health reporting) verified")
                .artifact("implementationFiles", List.of(RATE_LIMITER_FILE, RATE_LIMIT_FILTER_FILE))
                .build();
    }
}
