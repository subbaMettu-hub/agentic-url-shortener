package com.assessment.orchestrator.core;

import java.time.Instant;

public record ApprovalDecision(boolean approved, String approver, String comment, Instant timestamp) {

    public static ApprovalDecision approve(String approver, String comment) {
        return new ApprovalDecision(true, approver, comment, Instant.now());
    }

    public static ApprovalDecision reject(String approver, String comment) {
        return new ApprovalDecision(false, approver, comment, Instant.now());
    }
}
