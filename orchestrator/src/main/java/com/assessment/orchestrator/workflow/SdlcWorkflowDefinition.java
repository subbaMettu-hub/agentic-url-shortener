package com.assessment.orchestrator.workflow;

import com.assessment.orchestrator.agents.*;
import com.assessment.orchestrator.core.*;

import java.util.EnumSet;
import java.util.Set;

/**
 * Builds the SDLC workflow DAG:
 *
 * <pre>
 *                    REQUIREMENTS
 *                    /          \
 *            DESIGN_API   DESIGN_DATA_MODEL
 *                    \          /
 *                   IMPLEMENTATION
 *                          |
 *                  STATIC_ANALYSIS
 *                    /          \
 *              TESTING      DOCUMENTATION
 *                    \          /
 *                RELEASE_READINESS  (human approval gate)
 * </pre>
 *
 * DESIGN_API/DESIGN_DATA_MODEL and TESTING/DOCUMENTATION are genuine parallel branches that join
 * before their downstream stage - the same graph structure and engine handle every scenario;
 * only which {@link Agent} implementation is plugged into IMPLEMENTATION (and a couple of
 * per-scenario config values) changes per scenario.
 */
public final class SdlcWorkflowDefinition {

    private SdlcWorkflowDefinition() {
    }

    public static WorkflowGraph build(Agent implementationAgent, Set<StageId> injectFailureStages) {
        return WorkflowGraph.builder()
                .addStage(stage(StageId.REQUIREMENTS, "Requirement Understanding",
                        wrap(StageId.REQUIREMENTS, new RequirementsAgent(), injectFailureStages))
                        .retryPolicy(RetryPolicy.of(1, 0))
                        .rollbackHandler(new GenericRollbackAgent())
                        .build())

                .addStage(stage(StageId.DESIGN_API, "API Design",
                        wrap(StageId.DESIGN_API, new DesignApiAgent(), injectFailureStages))
                        .dependsOn(StageId.REQUIREMENTS)
                        .retryPolicy(RetryPolicy.of(1, 0))
                        .rollbackHandler(new GenericRollbackAgent())
                        .build())

                .addStage(stage(StageId.DESIGN_DATA_MODEL, "Data Model Design",
                        wrap(StageId.DESIGN_DATA_MODEL, new DesignDataModelAgent(), injectFailureStages))
                        .dependsOn(StageId.REQUIREMENTS)
                        .retryPolicy(RetryPolicy.of(1, 0))
                        .rollbackHandler(new GenericRollbackAgent())
                        .build())

                .addStage(stage(StageId.IMPLEMENTATION, "Implementation",
                        wrap(StageId.IMPLEMENTATION, implementationAgent, injectFailureStages))
                        .dependsOn(StageId.DESIGN_API, StageId.DESIGN_DATA_MODEL)
                        .retryPolicy(RetryPolicy.of(2, 200))
                        .rollbackHandler(new GenericRollbackAgent())
                        .build())

                .addStage(stage(StageId.STATIC_ANALYSIS, "Static Analysis",
                        wrap(StageId.STATIC_ANALYSIS, new StaticAnalysisAgent(), injectFailureStages))
                        .dependsOn(StageId.IMPLEMENTATION)
                        .retryPolicy(RetryPolicy.of(3, 300))
                        .rollbackHandler(new GenericRollbackAgent())
                        .build())

                .addStage(stage(StageId.TESTING, "Testing",
                        wrap(StageId.TESTING, new TestingAgent(), injectFailureStages))
                        .dependsOn(StageId.STATIC_ANALYSIS)
                        .retryPolicy(RetryPolicy.of(1, 0))
                        .rollbackHandler(new GenericRollbackAgent())
                        .build())

                .addStage(stage(StageId.DOCUMENTATION, "Documentation",
                        wrap(StageId.DOCUMENTATION, new DocumentationAgent(), injectFailureStages))
                        .dependsOn(StageId.STATIC_ANALYSIS)
                        .retryPolicy(RetryPolicy.of(1, 0))
                        .rollbackHandler(new GenericRollbackAgent())
                        .build())

                .addStage(stage(StageId.RELEASE_READINESS, "Release Readiness",
                        wrap(StageId.RELEASE_READINESS, new ReleaseReadinessAgent(), injectFailureStages))
                        .dependsOn(StageId.TESTING, StageId.DOCUMENTATION)
                        .retryPolicy(RetryPolicy.of(1, 0))
                        .requiresHumanApproval(true)
                        .rollbackHandler(new GenericRollbackAgent())
                        .exitGate(ctx -> {
                            Integer failures = ctx.hasArtifact("testFailures") ? ctx.getArtifact("testFailures") : null;
                            if (failures != null && failures > 0) {
                                return GateResult.fail("Cannot release with " + failures + " failing test(s)");
                            }
                            return GateResult.ok();
                        })
                        .build())

                .build();
    }

    private static StageNode.Builder stage(StageId id, String displayName, Agent agent) {
        return StageNode.builder(id, displayName, agent);
    }

    private static Agent wrap(StageId id, Agent real, Set<StageId> injectFailureStages) {
        if (injectFailureStages == null || injectFailureStages.isEmpty() || !injectFailureStages.contains(id)) {
            return real;
        }
        return new ChaosAgent(id.name());
    }

    public static Set<StageId> noFailureInjection() {
        return EnumSet.noneOf(StageId.class);
    }
}
