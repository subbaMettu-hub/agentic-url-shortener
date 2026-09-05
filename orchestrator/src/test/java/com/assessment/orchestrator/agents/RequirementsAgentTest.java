package com.assessment.orchestrator.agents;

import com.assessment.orchestrator.core.ExecutionContext;
import com.assessment.orchestrator.core.StageId;
import com.assessment.orchestrator.core.StageNode;
import com.assessment.orchestrator.core.StageOutcome;
import com.assessment.orchestrator.domain.RequirementSpec;
import com.assessment.orchestrator.llm.ReasoningProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementsAgentTest {

    private static final StageNode NOOP_STAGE = StageNode.builder(StageId.REQUIREMENTS, "Requirements",
            (ctx, self, attempt) -> StageOutcome.success("noop")).build();

    private ExecutionContext newContext(Path tmp, String rawRequirement) {
        return new ExecutionContext("test-run", "unit-test", rawRequirement, tmp, tmp, Map.of());
    }

    private static final class FakeProvider implements ReasoningProvider {
        private final boolean available;
        private final Optional<String> response;

        FakeProvider(boolean available, Optional<String> response) {
            this.available = available;
            this.response = response;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Optional<String> complete(String systemPrompt, String userPrompt) {
            return response;
        }
    }

    @Test
    void fallsBackToHeuristicWhenProviderUnavailable(@TempDir Path tmp) throws Exception {
        RequirementsAgent agent = new RequirementsAgent(new FakeProvider(false, Optional.empty()));

        StageOutcome outcome = agent.execute(newContext(tmp, "Make the service more reliable"), NOOP_STAGE, 1);

        RequirementSpec spec = outcome.getArtifacts().get("requirementSpec") instanceof RequirementSpec s ? s : null;
        assertThat(spec).isNotNull();
        assertThat(spec.ambiguous()).isTrue();
        assertThat(spec.normalizedGoal()).contains("operational reliability");
        assertThat(Files.readString(tmp.resolve("01-requirements.md"))).contains("deterministic heuristic");
    }

    @Test
    void usesLlmAnalysisWhenProviderReturnsValidJson(@TempDir Path tmp) throws Exception {
        String json = """
                ```json
                {
                  "normalizedGoal": "Add a bulk-delete endpoint for expired short URLs",
                  "ambiguous": false,
                  "inScope": ["DELETE /api/v1/urls/expired"],
                  "outOfScope": ["Scheduled automatic cleanup"],
                  "acceptanceCriteria": ["Expired URLs are removed and return 404 afterward"],
                  "assumptions": [{"id": "A1", "statement": "Soft-deleted rows are excluded", "rationale": "Consistent with existing deactivate semantics", "riskLevel": "LOW"}],
                  "openQuestions": []
                }
                ```
                """;
        RequirementsAgent agent = new RequirementsAgent(new FakeProvider(true, Optional.of(json)));

        StageOutcome outcome = agent.execute(newContext(tmp, "Add bulk delete for expired URLs"), NOOP_STAGE, 1);

        RequirementSpec spec = outcome.getArtifacts().get("requirementSpec") instanceof RequirementSpec s ? s : null;
        assertThat(spec).isNotNull();
        assertThat(spec.ambiguous()).isFalse();
        assertThat(spec.normalizedGoal()).isEqualTo("Add a bulk-delete endpoint for expired short URLs");
        assertThat(spec.assumptions()).hasSize(1);
        assertThat(spec.assumptions().get(0).riskLevel()).isEqualTo("LOW");
        assertThat(Files.readString(tmp.resolve("01-requirements.md"))).contains("Claude API (live reasoning)");
    }

    @Test
    void fallsBackToHeuristicWhenLlmReturnsMalformedJson(@TempDir Path tmp) throws Exception {
        RequirementsAgent agent = new RequirementsAgent(new FakeProvider(true, Optional.of("not json at all")));

        StageOutcome outcome = agent.execute(newContext(tmp, "Make the service more reliable"), NOOP_STAGE, 1);

        RequirementSpec spec = outcome.getArtifacts().get("requirementSpec") instanceof RequirementSpec s ? s : null;
        assertThat(spec).isNotNull();
        assertThat(spec.ambiguous()).isTrue();
        assertThat(Files.readString(tmp.resolve("01-requirements.md"))).contains("deterministic heuristic");
    }

    @Test
    void fallsBackToHeuristicWhenProviderCallFails(@TempDir Path tmp) throws Exception {
        RequirementsAgent agent = new RequirementsAgent(new FakeProvider(true, Optional.empty()));

        StageOutcome outcome = agent.execute(newContext(tmp, "Make the service more reliable"), NOOP_STAGE, 1);

        RequirementSpec spec = outcome.getArtifacts().get("requirementSpec") instanceof RequirementSpec s ? s : null;
        assertThat(spec).isNotNull();
        assertThat(spec.ambiguous()).isTrue();
        assertThat(Files.readString(tmp.resolve("01-requirements.md"))).contains("deterministic heuristic");
    }
}
