# Final Engineering Summary

## Plan and rationale

The assessment asks for two things at once: a working URL shortener, and an agentic orchestration
layer that demonstrates governed, non-linear SDLC automation. Building them as one monolith would
have made it impossible to tell which part was "the product" and which part was "the agentic
system," so the plan from the start was two independent Maven modules
(`docs/ARCHITECTURE.md` -> 1) with a one-directional dependency (orchestrator reads/tests the
product; the product knows nothing about the orchestrator).

Sequencing:

1. Build the product first (`url-shortener`) with real tests passing, so the orchestrator has a
   real codebase to reason about and a real test suite to run - not a stub.
2. Build the orchestration **engine** (`orchestrator/core`) as a generic DAG scheduler with no
   knowledge of URL shortening, and prove its governance mechanics (parallel execution, retries,
   rollback, policy, human approval, re-planning, safe-stop) against synthetic agents in
   `OrchestratorEngineTest` *before* wiring in real SDLC agents - so engine bugs were caught
   cheaply, without a real `mvn test` invocation in the loop.
3. Build the real SDLC agents (`orchestrator/agents`) and the three scenario definitions
   (`orchestrator/scenarios`), and actually run all three end to end, reading the generated
   `report.md`/`audit.jsonl` for each to verify the mechanics show up in a real run, not just in
   unit tests (see `docs/SCENARIOS.md`).
4. Write this documentation from the actual, executed artifacts rather than from the plan - every
   figure in `docs/SCENARIOS.md` (impacted-file counts, retry counts, plan versions, MTTR) is read
   out of a committed run under `orchestrator/sample-runs/`, not asserted from memory.

Key technology decisions and why (see `docs/ARCHITECTURE.md` for the full rationale on each):

- **Java 17 + Spring Boot** for the product - matches enterprise conventions, and gives a real
  dependency-injected, testable layering (controller/service/repository) to reason about.
- **A hand-built DAG scheduler, not a linear prompt chain**, for the orchestrator - the assessment
  explicitly requires non-linear, stateful execution with governance, which a linear chain cannot
  express (no true parallelism, no re-entry of an earlier stage, no place for a retry policy).
- **Deterministic, simulated agents rather than live LLM calls** - reproducible for a reviewer with
  zero API keys/cost, while the `Agent` interface makes swapping in a real LLM-backed
  implementation a localized, one-class change (`docs/ARCHITECTURE.md` -> 3.6).

## Artifacts delivered

- `url-shortener/` - working service: create/redirect/analytics/QR/rate-limit/cache/health, 39
  passing tests.
- `orchestrator/` - the orchestration engine, 8 passing engine tests, 3 scenario definitions, a
  CLI (`OrchestratorCli`), and policy guardrails (`StandardPolicies`).
- `orchestrator/sample-runs/` - committed, real output from executed runs: normalized
  requirements, design docs, static analysis reports, real test logs, documentation, release
  readiness reports, `audit.jsonl`, `metrics.json`, `report.md` - for greenfield, brownfield,
  ambiguous, and one failure-injection run.
- `docs/ARCHITECTURE.md`, `docs/SCENARIOS.md`, `docs/TESTING.md` (this file's companions),
  `docs/CHANGELOG.md` (appended to by the orchestrator's own Documentation stage on every run).
- `README.md` - setup and quick start.

## Risks, trade-offs, and how they're mitigated

| Risk / trade-off | Mitigation / why it's acceptable here |
|---|---|
| Simulated agents could be seen as "not really agentic" | The orchestration model - the part the assessment names as the critical differentiator - is real: real parallel execution on real threads, real retry/backoff, real rollback, a real blocking human-approval gate, a real policy engine, a real audit log, real dynamic re-planning. What's simulated is *the content each agent's reasoning produces*, and even that is grounded in real actions where it matters most: real codebase greps, a real `mvn test` invocation, real file-presence verification against the shipped product. See `docs/ARCHITECTURE.md` -> 3.6 for the full honesty note. |
| Single-instance H2 + in-memory rate limiter won't survive a multi-instance deployment | Explicitly scoped out and documented, not silently ignored - this is precisely the assumption the *ambiguous* scenario's requirements agent flags, escalates through design, and resolves via a governed re-plan rather than a silent guess (`docs/SCENARIOS.md` -> 3). |
| `TestingAgent` shelling out to a real `mvn test` makes scenario runs slower (~15-20s) and depends on Maven being resolvable on PATH | Deliberate: a "testing agent" that doesn't actually run tests proves nothing. Documented as a trade-off in `docs/TESTING.md`, with the mitigation that a narrower, impacted-files-only test selection is a natural next step (the impact analysis to drive it already exists in `DesignApiAgent`'s output). |
| Auto-approval mode (`AutoApprovalPort`) could be mistaken for a real human sign-off | Every auto-approval decision is labeled `"(demo mode)"` in the audit trail and decision lineage, both in the code (`AutoApprovalPort`'s own comment) and in every generated report - it is never presented as human oversight, and `--interactive` swaps in a real blocking console prompt when genuine human-in-the-loop behavior is wanted. |
| Re-planning could loop forever if agents keep flagging replans | Bounded: capped at 3 replans per run (`OrchestratorEngine.MAX_REPLANS_PER_RUN`); exceeding it forces a safe-stop rather than looping silently. |
| Policy rules are a small, illustrative set (secrets, static-analysis-clean, release-requires-green-tests) | This is a prototype demonstrating the *mechanism* (a central `PolicyEngine` every stage's outcome passes through, with BLOCK vs WARN severity), not a claim of comprehensive security/compliance coverage - adding a rule is a one-function change (`PolicyRule.of(...)`), not a framework change. |

## Assumptions

1. A reviewer running this has a JDK 17+ and can get Maven on PATH (either pre-installed or via
   the JetBrains-bundled JDK + a downloaded Maven binary, both covered in `README.md`); internet
   access is available for the first Maven Central dependency download.
2. "Working prototype" means genuinely runnable end-to-end on a reviewer's machine, not a
   description of what it would do - this is why `TestingAgent` performs a real build/test
   invocation rather than a simulated one, and why `orchestrator/sample-runs/` contains real,
   inspectable output rather than illustrative mockups.
3. The three required scenarios (greenfield/brownfield/ambiguous) should exercise materially
   different codepaths in the *same* engine, not three different engines dressed up differently -
   this drove the decision to keep the workflow graph and engine identical across scenarios and
   vary only the raw requirement text and the `IMPLEMENTATION` agent.

## Limitations

- No real LLM is called at any point (by design - see the risk table above); the reasoning quality
  of the simulated agents is bounded by the heuristics/checks their authors (Claude, working with
  the assessment's author) wrote, not by genuine language understanding.
- Single-node only: no distributed rate limiting, no multi-instance cache coherence, no HA - all
  explicitly documented rather than silently assumed (see `docs/ARCHITECTURE.md` and the
  *ambiguous* scenario).
- The orchestrator's policy rule set, retry/backoff tuning, and MTTR calculation are illustrative
  of the mechanism rather than tuned against real production incident data - there is no historical
  incident corpus for a brand-new prototype to tune against.
- No CI pipeline is included (e.g. a GitHub Actions workflow running both modules' test suites on
  every push) - out of scope for a local assessment deliverable, but a natural next step given the
  test suites already exist and pass locally.
