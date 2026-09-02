package com.assessment.orchestrator.core;

/** The SDLC stages coordinated by the orchestrator, forming the nodes of the workflow DAG. */
public enum StageId {
    REQUIREMENTS,
    DESIGN_API,
    DESIGN_DATA_MODEL,
    IMPLEMENTATION,
    STATIC_ANALYSIS,
    TESTING,
    DOCUMENTATION,
    RELEASE_READINESS
}
