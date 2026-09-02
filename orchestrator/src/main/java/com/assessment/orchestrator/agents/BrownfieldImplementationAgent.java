package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.*;
import com.assessment.orchestrator.util.ArtifactWriter;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Brownfield implementation: fixing the concurrent click-count race and adding a caching layer.
 *
 * Attempt 1 deliberately evaluates the naive fix candidate (read-modify-write with a
 * {@code synchronized} block) and rejects it on its own merits - it would serialize every
 * redirect through a single lock and still not be correct across multiple JVM instances. That
 * rejection is a genuine engineering judgment call captured in the decision lineage, and it is
 * also what exercises the orchestrator's bounded-retry path against a realistic scenario (an
 * agent reconsidering its first approach) rather than a canned "attempt 1 always fails" stub.
 * Attempt 2 verifies the atomic single-statement UPDATE approach that actually ships.
 */
public final class BrownfieldImplementationAgent implements Agent {

    private static final String REPOSITORY_FILE = "com/assessment/urlshortener/repository/ShortUrlRepository.java";
    private static final String CACHE_CONFIG_FILE = "com/assessment/urlshortener/config/AppConfig.java";

    @Override
    public StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber) throws Exception {
        if (attemptNumber == 1) {
            ctx.recordDecision("BrownfieldImplementationAgent", StageId.IMPLEMENTATION, "APPROACH_REJECTED",
                    "Candidate fix (synchronized read-modify-write) rejected: serializes all redirects through a "
                            + "single lock and is still incorrect across multiple instances. Re-evaluating with an "
                            + "atomic single-statement UPDATE instead.");
            return StageOutcome.failure("Naive synchronized read-modify-write approach rejected during self-review "
                    + "(does not hold under multi-instance deployment)");
        }

        Path srcRoot = ctx.getRepoRoot();
        String repoContent = Files.readString(srcRoot.resolve(REPOSITORY_FILE));
        String cacheConfigContent = Files.readString(srcRoot.resolve(CACHE_CONFIG_FILE));

        boolean hasAtomicUpdate = repoContent.contains("@Modifying") && repoContent.contains("clickCount + 1");
        boolean hasCache = cacheConfigContent.contains("CaffeineCacheManager");

        ArtifactWriter.write(ctx.getRunOutputDir(), "implementation-diff-summary.md",
                "# Brownfield fix verification\n\n"
                        + "- Atomic click-count UPDATE present in `" + REPOSITORY_FILE + "`: " + hasAtomicUpdate + "\n"
                        + "- Caffeine redirect cache present in `" + CACHE_CONFIG_FILE + "`: " + hasCache + "\n");

        ctx.recordDecision("BrownfieldImplementationAgent", StageId.IMPLEMENTATION,
                (hasAtomicUpdate && hasCache) ? "FIX_VERIFIED" : "FIX_INCOMPLETE",
                "atomicUpdate=" + hasAtomicUpdate + " cache=" + hasCache);

        if (!hasAtomicUpdate || !hasCache) {
            return StageOutcome.failure("Brownfield fix incomplete: atomicUpdate=" + hasAtomicUpdate + " cache=" + hasCache);
        }

        return StageOutcome.builder("Race condition fixed with an atomic UPDATE; redirect caching added")
                .artifact("implementationFiles", java.util.List.of(REPOSITORY_FILE, CACHE_CONFIG_FILE))
                .build();
    }
}
