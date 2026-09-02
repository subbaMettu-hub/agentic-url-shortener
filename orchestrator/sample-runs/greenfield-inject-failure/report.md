# Run Report - Greenfield: QR code generation for short URLs

- Run ID: greenfield-20260902-154926
- Final status: FAILED
- Plan version reached: 1

## Final stage statuses

| Stage | Status |
|---|---|
| REQUIREMENTS | PASSED |
| DESIGN_API | PASSED |
| DESIGN_DATA_MODEL | PASSED |
| IMPLEMENTATION | PASSED |
| STATIC_ANALYSIS | ROLLED_BACK |
| TESTING | PENDING |
| DOCUMENTATION | PENDING |
| RELEASE_READINESS | PENDING |

## Reliability metrics

| Metric | Value |
|---|---|
| Success rate | 0.8 |
| Total retries | 2 |
| Retry frequency (per stage) | 0.4 |
| Total rollbacks | 1 |
| Rollback frequency (per stage) | 0.2 |
| MTTR (ms) | 0 |
| End-to-end latency (ms) | 821 |

## Decision lineage

- `2026-09-02T20:49:26.748977900Z` [plan v1] **RequirementsAgent** @ REQUIREMENTS -> REQUIREMENT_NORMALIZED: Raw ask was sufficiently well-defined; normalized directly into acceptance criteria.
- `2026-09-02T20:49:26.800899400Z` [plan v1] **DesignApiAgent** @ DESIGN_API -> API_DESIGN_COMPLETE: New surface area; no existing endpoints/classes impacted.
- `2026-09-02T20:49:26.800899400Z` [plan v1] **DesignDataModelAgent** @ DESIGN_DATA_MODEL -> DATA_MODEL_REVIEW_COMPLETE: No schema migration required; change composes with the existing ShortUrl entity.
- `2026-09-02T20:49:26.818154700Z` [plan v1] **GreenfieldImplementationAgent** @ IMPLEMENTATION -> IMPLEMENTATION_VERIFIED: QR code feature files present and snapshotted as evidence: [com/assessment/urlshortener/service/QrCodeService.java, com/assessment/urlshortener/controller/QrCodeController.java]
- `2026-09-02T20:49:27.441585900Z` [plan v1] **GenericRollbackAgent** @ STATIC_ANALYSIS -> ROLLED_BACK: Discarded partial artifacts for STATIC_ANALYSIS after exhausting retries; downstream stages will not proceed on top of an unverified result.

## Stage-level metrics

| Stage | Attempts | Retries | Rollbacks | Passed | MTTR (ms) |
|---|---|---|---|---|---|
| IMPLEMENTATION | 1 | 0 | 0 | true | - |
| STATIC_ANALYSIS | 3 | 2 | 1 | false | - |
| REQUIREMENTS | 1 | 0 | 0 | true | - |
| DESIGN_API | 1 | 0 | 0 | true | - |
| DESIGN_DATA_MODEL | 1 | 0 | 0 | true | - |

Generated 2026-09-02T20:49:27.476018700Z. See audit.jsonl in this directory for the full, machine-readable event stream this report is derived from.
