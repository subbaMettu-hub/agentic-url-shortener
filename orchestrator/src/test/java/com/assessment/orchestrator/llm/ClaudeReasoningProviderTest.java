package com.assessment.orchestrator.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeReasoningProviderTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void isUnavailableWithoutAnApiKey(String key) {
        ClaudeReasoningProvider provider = new ClaudeReasoningProvider(key, "claude-haiku-4-5-20251001");
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void completeReturnsEmptyRatherThanThrowingWhenUnavailable() {
        ClaudeReasoningProvider provider = new ClaudeReasoningProvider(null, "claude-haiku-4-5-20251001");
        Optional<String> result = provider.complete("system", "user");
        assertThat(result).isEmpty();
    }

    @Test
    void isAvailableWithABlankLookingButNonBlankKey() {
        ClaudeReasoningProvider provider = new ClaudeReasoningProvider("sk-ant-fake-key-for-test", "claude-haiku-4-5-20251001");
        assertThat(provider.isAvailable()).isTrue();
    }
}
