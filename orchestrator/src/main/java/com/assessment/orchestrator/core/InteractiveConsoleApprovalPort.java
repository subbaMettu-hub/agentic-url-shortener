package com.assessment.orchestrator.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Real human-in-the-loop approval: blocks the run and prompts on stdin. Used with --interactive. */
public final class InteractiveConsoleApprovalPort implements HumanApprovalPort {

    @Override
    public ApprovalDecision requestApproval(ExecutionContext ctx, StageNode stage, StageOutcome outcome) {
        System.out.println();
        System.out.println("================ HUMAN APPROVAL REQUIRED ================");
        System.out.println("Stage      : " + stage.getDisplayName() + " (" + stage.getId() + ")");
        System.out.println("Outcome    : " + (outcome.isSuccess() ? "SUCCESS" : "FAILURE"));
        System.out.println("Summary    : " + outcome.getSummary());
        System.out.print("Approve this stage and continue the run? [y/N]: ");
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line = reader.readLine();
            boolean approved = line != null && line.trim().equalsIgnoreCase("y");
            System.out.println(approved ? "-> Approved." : "-> Rejected.");
            System.out.println("===========================================================");
            return approved
                    ? ApprovalDecision.approve("interactive-reviewer", "Approved via console prompt")
                    : ApprovalDecision.reject("interactive-reviewer", "Rejected via console prompt");
        } catch (IOException e) {
            System.out.println("-> Failed to read approval input, defaulting to reject: " + e.getMessage());
            return ApprovalDecision.reject("interactive-reviewer", "stdin read failure: " + e.getMessage());
        }
    }
}
