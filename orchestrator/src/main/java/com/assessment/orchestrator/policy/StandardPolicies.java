package com.assessment.orchestrator.policy;

import com.assessment.orchestrator.core.*;

import java.util.List;
import java.util.Optional;

/**
 * Governance guardrails applied uniformly to every stage of every scenario, independent of which
 * agent produced the outcome - this is the "policy guardrails for security, compliance, and
 * change control" the assessment asks for, expressed as data rather than scattered ad hoc checks.
 */
public final class StandardPolicies {

    private StandardPolicies() {
    }

    public static PolicyEngine standard() {
        return new PolicyEngine()
                .register(noHardcodedSecrets())
                .register(staticAnalysisMustBeClean())
                .register(releaseRequiresGreenTests());
    }

    /** BLOCK: never let a stage pass if it reports an artifact containing an obvious secret pattern. */
    private static PolicyRule noHardcodedSecrets() {
        return PolicyRule.of("no-hardcoded-secrets", (stageId, outcome) -> {
            for (Object value : outcome.getArtifacts().values()) {
                String s = String.valueOf(value);
                if (s.contains("password=") || s.contains("apiKey=\"") || s.contains("BEGIN PRIVATE KEY")) {
                    return Optional.of(new PolicyViolation("no-hardcoded-secrets", PolicySeverity.BLOCK,
                            "Artifact contains what looks like a hardcoded credential"));
                }
            }
            return Optional.empty();
        });
    }

    /** BLOCK: static analysis findings are treated as blocking change-control gates, not advisory. */
    @SuppressWarnings("unchecked")
    private static PolicyRule staticAnalysisMustBeClean() {
        return PolicyRule.of("static-analysis-must-be-clean", (stageId, outcome) -> {
            if (stageId != StageId.STATIC_ANALYSIS) {
                return Optional.empty();
            }
            Object raw = outcome.getArtifacts().get("staticAnalysisFindings");
            if (raw instanceof List<?> findings && !findings.isEmpty()) {
                return Optional.of(new PolicyViolation("static-analysis-must-be-clean", PolicySeverity.BLOCK,
                        findings.size() + " static analysis finding(s) must be resolved before proceeding: " + findings));
            }
            return Optional.empty();
        });
    }

    /** WARN: release readiness recommending a hold is surfaced but does not by itself block the stage; the
     *  human approval gate on RELEASE_READINESS is the actual authority. */
    private static PolicyRule releaseRequiresGreenTests() {
        return PolicyRule.of("release-requires-green-tests", (stageId, outcome) -> {
            if (stageId != StageId.RELEASE_READINESS || outcome.isSuccess()) {
                return Optional.empty();
            }
            return Optional.of(new PolicyViolation("release-requires-green-tests", PolicySeverity.WARN,
                    "Release readiness agent recommended HOLD: " + outcome.getSummary()));
        });
    }
}
