# Three Scenarios

All three runs below use the same workflow graph, engine, and policy set - only the raw
requirement text and the `IMPLEMENTATION` agent implementation differ per scenario. Every run
referenced here is committed verbatim under `orchestrator/sample-runs/<scenario>/` (audit.jsonl,
metrics.json, report.md, and every intermediate markdown artifact) - nothing below is
hand-written narrative; it's read out of those files. Reproduce any of them yourself with:

```powershell
mvn -f orchestrator/pom.xml compile exec:java "-Dexec.args=--scenario=<greenfield|brownfield|ambiguous>"
```

## 1. Greenfield - "add QR code generation"

**Raw requirement:**
> Add the ability for users to generate a QR code image for any shortened URL, accessible via a
> new endpoint, so links can be shared in print/offline contexts.

**Requirement understanding.** The ambiguity heuristic (`RequirementsAgent`) looks for vague
markers ("reliable", "better", "improve", ...) *without* a concrete technical term nearby
("endpoint", "QR", "API", ...). This requirement names a concrete endpoint and feature, so it's
classified **not ambiguous** - normalized directly into acceptance criteria, with one
low-risk, always-documented assumption about following existing conventions for unstated details
(image format, size defaults) rather than inventing new ones silently.

**Decomposition & orchestration.** `DESIGN_API` and `DESIGN_DATA_MODEL` run in parallel (both
depend only on `REQUIREMENTS`); the data-model branch finds no schema change needed, the API
branch finds zero existing files impacted (this is genuinely new surface area).
`IMPLEMENTATION` (`GreenfieldImplementationAgent`) verifies `QrCodeService.java` and
`QrCodeController.java` exist in the real `url-shortener` module and snapshots them into the run's
evidence folder. `STATIC_ANALYSIS`, then `TESTING` (a real `mvn test` run - 39/39 passing) and
`DOCUMENTATION` in parallel, then `RELEASE_READINESS`, which is approved at the human-approval
gate and completes the run.

**Result:** `COMPLETED`, success rate 1.0, 0 retries, 0 rollbacks - the clean happy path.

## 2. Brownfield - "fix the click-count race, add caching"

**Raw requirement:**
> The click analytics endpoint under-counts clicks under concurrent redirects to the same short
> code (a classic read-modify-write race), and hot redirects are hitting the database on every
> request. Fix the click counting bug and add a caching layer for hot redirects to reduce DB load.

**Codebase reasoning.** `DesignApiAgent` doesn't just describe the fix in the abstract - it walks
the real `url-shortener` source tree, matches requirement keywords ("click"/"count" ->
click-counting concepts, "cache" -> caching concepts) against file names and contents, and reports
back **8 real impacted files**, including `ShortUrlRepository.java`, `UrlShortenerService.java`,
`RedirectController.java`, `AppConfig.java`, `AnalyticsResponse.java`, `UrlMetadataResponse.java`,
`ShortUrl.java`, and `UrlController.java` - a genuine impact analysis over the actual codebase, not
a scenario-specific script.

**Bounded retries, twice, for two different reasons:**

1. `IMPLEMENTATION` (`BrownfieldImplementationAgent`) attempt 1 evaluates the "obvious" fix - a
   `synchronized` read-modify-write - and *rejects its own candidate*: it would serialize every
   redirect through one lock and still be wrong under more than one instance. That rejection is
   logged as a real decision (`APPROACH_REJECTED`), the stage fails, and the engine retries.
   Attempt 2 verifies the atomic single-statement `UPDATE` approach that actually ships in
   `ShortUrlRepository.incrementClickCount` and passes.
2. `STATIC_ANALYSIS` is configured for this scenario with one simulated transient tool failure
   (`staticAnalysisFlakyAttempts=1`, representing a scanner timeout) before it runs the real scan
   and passes clean.

**Result:** `COMPLETED`, success rate 1.0, **2 total retries** (retry frequency 0.25/stage), 0
rollbacks, MTTR ~20.6s (dominated by the real `mvn test` run each recovering stage waited behind),
end-to-end latency ~20.7s. Full decision lineage and metrics: `orchestrator/sample-runs/brownfield/report.md`.

## 3. Ambiguous - "make the URL shortener more reliable"

**Raw requirement:**
> Make the URL shortener more reliable.

**Ambiguity detection and documented assumptions.** "Reliable" hits the ambiguity heuristic (a
vague marker with no concrete term nearby) - `RequirementsAgent` does **not** silently invent a
scope. It normalizes a working interpretation (bounded rate limiting + accurate health signaling,
explicitly *not* HA/multi-region/SLA work) and records two assumptions, one of them high-risk:

> **A2** [HIGH]: An in-memory, single-node token-bucket rate limiter is sufficient for this iteration.

**Dynamic re-planning, for real.** `DESIGN_API` reviews the normalized spec and finds assumption
A2 unresolved and high-risk - a single-node limiter is a real design constraint that should be a
conscious decision, not something design quietly builds on top of. Rather than proceeding, it
returns `requiresReplan(REQUIREMENTS, ...)`. The engine:

1. Bumps the plan version to **v2**.
2. Computes every stage transitively downstream of `REQUIREMENTS` - all seven other stages - and
   marks them `STALE`.
3. Re-enters `REQUIREMENTS`, which produces a **revision**: assumption A2 is explicitly accepted
   as a documented MVP limitation (not multi-instance rate limiting, which would be a materially
   larger, differently-scoped change) and the open question about multi-instance deployment is
   marked resolved-by-scoping.
4. The scheduler cascades forward again from the revised requirement - design, implementation,
   static analysis, testing, documentation, release readiness - all re-run against the v2 spec and
   pass.

This is not a scripted "ambiguous scenarios always replan" branch - `DesignApiAgent`'s check is
generic (`spec.revision() == 1 && any HIGH-risk assumption`) and would fire for *any* scenario
whose normalized spec carried an unresolved high-risk assumption into design; it simply never
triggers for greenfield/brownfield because their assumptions are LOW risk.

**Result:** `COMPLETED`, plan version reached **2**, success rate 1.0 (every stage that ran to
completion passed), 0 retries/rollbacks in this trace (re-planning is a distinct governance
mechanism from retry - see `docs/ARCHITECTURE.md` -> 3.4). Full lineage:
`orchestrator/sample-runs/ambiguous/report.md`.

## Seeing failure handling for real

The three scenarios above all end in a release, by design - they demonstrate the
governance-*success* path (retries recovering, re-planning resolving a conflict, human approval
authorizing). The retry-exhaustion -> rollback -> safe-stop path is real and engine-tested
(`OrchestratorEngineTest.exhaustsRetriesTriggersRollbackAndFailsRun`,
`...safeStopPreventsNewStagesFromStarting`), and reviewable live with an explicit, opt-in flag
rather than a manufactured failure hidden in a "successful" narrative:

```powershell
mvn -f orchestrator/pom.xml compile exec:java `
  "-Dexec.args=--scenario=greenfield --inject-failure=STATIC_ANALYSIS"
```

`STATIC_ANALYSIS`'s real agent is swapped for one that always fails. With its configured retry
policy (3 attempts, 300ms backoff) exhausted, the engine runs its rollback handler
(`ROLLED_BACK`), and because `TESTING`/`DOCUMENTATION`/`RELEASE_READINESS` all depend
(transitively) on `STATIC_ANALYSIS` passing, no further stage can start - the engine declares a
**safe-stop** and ends the run `FAILED` rather than proceeding on an inconsistent state. A
committed trace of exactly this run is at `orchestrator/sample-runs/greenfield-inject-failure/`.
