# Architecture

## 1. Two systems, one repository

| | `url-shortener/` | `orchestrator/` |
|---|---|---|
| What it is | The product being built | The agentic system that builds/evolves it |
| Depends on | Spring Boot, H2, Caffeine, ZXing | Nothing but the JDK + Jackson (for JSON audit/metrics output) |
| Knows about the other module? | No | Yes - reads its source tree and runs its test suite |

They are deliberately independent Maven modules with a one-directional dependency (orchestrator ->
url-shortener source, read-only; never the reverse). The orchestrator is a generic DAG-based SDLC
engine that happens to be instantiated, in this repo, against the URL shortener - nothing about
`orchestrator/core` is specific to URL shortening. That separation is itself an architectural
decision: it demonstrates the orchestration layer is a reusable capability, not glue code wedged
into the product.

## 2. `url-shortener` - the product

Standard layered Spring Boot service:

```
controller/   UrlController (CRUD+analytics), RedirectController, QrCodeController
service/      UrlShortenerService, QrCodeService, RateLimiterService, ShortCodeGenerator
repository/   ShortUrlRepository (Spring Data JPA, one atomic UPDATE for click counting)
model/        ShortUrl entity
dto/          Request/response records
exception/    Domain exceptions + a single @RestControllerAdvice -> consistent ApiErrorResponse
config/       AppProperties, AppConfig (Caffeine cache), RateLimitFilter
```

Key engineering decisions and why:

- **Click counting is a single atomic `UPDATE ... SET clickCount = clickCount + 1`**
  (`ShortUrlRepository.incrementClickCount`), not a read-modify-write. A naive
  `count = get(); count++; save(count)` loses updates whenever two redirects for the same code
  land concurrently - two threads can read the same starting value before either writes back.
  `ConcurrentClickCountTest` fires 100 concurrent redirects at one short code and asserts the
  count is exactly 100; it would flake under the naive implementation and is deterministic under
  the atomic one. This is also the concrete bug the brownfield scenario reasons about and fixes.
- **Redirect resolution is cached** (`@Cacheable` with Caffeine, 10k entries / 5 min TTL) because
  redirects are the highest-QPS path and are read-mostly. Click-count writes are *not* cached
  (they always hit the atomic UPDATE), so caching never causes a lost click. Deletes evict the
  cache key so a removed link can't keep resolving from a stale entry.
- **Short codes are random Base62, not sequential**, to avoid leaking creation order/volume and to
  avoid the two-write dance a sequential-ID-derived code would need (save to get an ID, then
  update with the derived code). Collisions are checked and retried (bounded at 5 attempts).
- **URL validation allows only `http`/`https`** with a non-blank host, rejecting `javascript:`,
  `data:`, and `file:` schemes - the standard URL-shortener abuse vector for XSS/local-file tricks.
- **Rate limiting is disabled by default** (`app.rate-limit.enabled=false`) - a token bucket exists
  and is fully tested, but is opt-in so it doesn't surprise existing callers/tests. This exact
  scoping decision is what the *ambiguous* scenario's requirements agent has to make explicit and
  document, rather than silently assuming.
- **Persistence is H2** (file-backed at runtime, in-memory for tests) to keep the prototype
  runnable with zero external infrastructure. Documented as a known limitation in
  `docs/FINAL_SUMMARY.md` - a real deployment would use Postgres/MySQL and a shared cache (Redis)
  if it needed to run as more than one instance (see the rate-limiter and cache scoping notes above).

## 3. `orchestrator` - the agentic SDLC layer

### 3.1 Why a hand-built DAG engine instead of a chat loop

A "chain of prompts" (requirements prompt -> design prompt -> code prompt -> ...) is a linear
pipeline: it cannot express two stages happening at once, cannot express "go back and redo an
earlier stage because a later one invalidated it," has no place for a retry policy, a human
approval that actually blocks progress, or an audit trail that survives the process exiting. The
assessment explicitly asks for *non-linear, stateful execution with governance*, so the
orchestrator is built as an explicit graph + scheduler, not a script:

```
                    REQUIREMENTS
                    /          \
            DESIGN_API   DESIGN_DATA_MODEL         <- run concurrently, join before next stage
                    \          /
                   IMPLEMENTATION
                          |
                  STATIC_ANALYSIS
                    /          \
              TESTING      DOCUMENTATION            <- run concurrently, join before release
                    \          /
                RELEASE_READINESS  <- human approval gate (HumanApprovalPort)
```

`WorkflowGraph` (`orchestrator/core/WorkflowGraph.java`) is validated acyclic at construction time
and exposes `transitiveDependents(stageId)`, which the re-planning path uses to know exactly which
downstream work becomes stale when an upstream stage's output changes.

### 3.2 The scheduling loop (`OrchestratorEngine`)

Each iteration: compute the set of stages whose dependencies have all `PASSED` and which aren't
already running; submit all of them to a worker pool (so `DESIGN_API`/`DESIGN_DATA_MODEL` and
`TESTING`/`DOCUMENTATION` genuinely execute in parallel, on real threads); block only until the
*next* stage finishes (not the whole batch) so a fast branch immediately frees its dependents
instead of waiting on a slower sibling. This is what makes "parallel with synchronization" real
rather than a linear order with a parallel-sounding label - verified in
`OrchestratorEngineTest.executesDiamondDagRespectingDependencyOrder`.

Per-stage execution, in order:

1. **Entry gate** - a precondition check (`StageNode.checkEntryGate`); failing it blocks the stage
   immediately (`BLOCKED_BY_POLICY`), no retries (a gate failure is deterministic, retrying won't
   change it).
2. **Agent execution**, inside a bounded retry loop (`RetryPolicy`: max attempts + linear backoff).
   Exceptions are caught and treated as a failed attempt, never crash the scheduler thread.
3. **Policy evaluation** (`PolicyEngine`) - every stage's outcome, regardless of which agent
   produced it, runs through the same registered rules (`orchestrator/policy/StandardPolicies`).
   A `BLOCK` violation fails the stage outright even if the agent reported success - governance
   overrides the agent. A `WARN` is recorded but doesn't stop the run.
4. **Exit gate** - a postcondition check (e.g. Release Readiness's exit gate refuses to pass if
   `testFailures > 0`, as a second, independent check even if the agent's own logic had a bug).
5. **Human approval**, only for stages with `requiresHumanApproval(true)` (currently
   `RELEASE_READINESS`) - the agent can recommend, but only the configured `HumanApprovalPort`
   can actually let the stage pass. Default demo runs use `AutoApprovalPort`, which applies a
   fixed, transparent rule and labels its decision "(demo mode)" in the audit trail rather than
   pretending a human looked at it; `--interactive` swaps in
   `InteractiveConsoleApprovalPort`, which genuinely blocks on a console y/N prompt.
6. On success: artifacts merge into the shared `ExecutionContext`, the stage is marked `PASSED`,
   and if the agent flagged `requiresReplan`, re-planning kicks in (3.4).
7. On exhausting all retries: the stage is marked `FAILED`, its `rollbackHandler` (if any) runs a
   compensating action and the stage becomes `ROLLED_BACK`. Because downstream stages require
   their dependencies to be `PASSED`, a rolled-back stage naturally blocks everything after it;
   once nothing is running and nothing is runnable, the engine declares a **safe-stop**
   (`RunStatus.FAILED`, with the exact stage-status snapshot logged) rather than continuing on an
   inconsistent state. See `docs/SCENARIOS.md` -> "Seeing failure handling for real" for a live
   trace of this path (bounded retries exhaust -> rollback -> safe-stop), and
   `OrchestratorEngineTest.exhaustsRetriesTriggersRollbackAndFailsRun` for the unit-level proof.
8. A separate, external `engine.requestSafeStop(reason)` lets already-running stages finish but
   starts no new ones - used internally when a run exceeds its `--timeout-seconds` budget, and
   exposed as a general API for an external caller to halt a run safely.

### 3.3 Cross-stage context and decision lineage

`ExecutionContext` is the one object every stage (possibly running concurrently) reads from and
writes to: a `ConcurrentHashMap` of named artifacts (the normalized requirement spec, design
notes, implementation evidence, test results, ...) and a `CopyOnWriteArrayList<DecisionRecord>` -
an append-only, timestamped log of *who decided what and why* (agent, policy engine, or human),
tagged with the plan version it happened under. Every generated report
(`orchestrator/runs/<scenario>/<runId>/report.md`) renders this lineage directly from the context,
so "why did the run end up here" is always answerable from data, not from re-reading code.

### 3.4 Dynamic re-planning

An agent can return `StageOutcome.requiresReplan(targetStage, reason)` even on a *successful*
outcome for itself - "my own stage is fine, but I've discovered an earlier stage's output doesn't
hold." The engine then:

1. Bumps `ExecutionContext.planVersion`.
2. Computes `target ∪ transitiveDependents(target)` from the `WorkflowGraph` - every stage that
   built on the now-invalid output, including the one that raised the flag (it depends on
   `target` transitively, so it's in that set too).
3. Marks all of them `STALE` and records a `REPLAN` audit event plus a `REPLAN_TRIGGERED`
   decision record with the reason.
4. The normal scheduling loop picks it up from there: `STALE` is treated exactly like `PENDING`,
   so `target` (having no unmet dependencies of its own, typically) becomes runnable on the very
   next iteration, and its dependents cascade back in as their own dependencies turn `PASSED`
   again - no separate re-planning code path, just the same scheduler running further.

Replans are capped at 3 per run (`OrchestratorEngine.MAX_REPLANS_PER_RUN`) and force a safe-stop
if exceeded, so a badly-behaved agent can't loop the run forever. See the *ambiguous* scenario in
`docs/SCENARIOS.md` for a full trace of this actually happening, and
`OrchestratorEngineTest.replanInvalidatesDownstreamAndRerunsThem` for the isolated unit proof.

### 3.5 Observability

- **`audit.jsonl`** - one JSON object per governance-relevant event (stage start/pass/fail/retry,
  policy violations, approvals, rollbacks, re-plans, safe-stops), append-only, written to disk as
  it happens (not buffered until the run ends) so a crashed run still leaves a usable trail.
- **`metrics.json`** / the metrics table in `report.md` - `MetricsCollector` tracks per-stage
  attempts, retries, rollbacks, and pass/fail timing, then rolls up: success rate, total
  retries/rollbacks and their per-stage frequency, **MTTR** (mean time between a stage's first
  failure and its eventual pass, across stages that recovered), and end-to-end run latency - the
  exact reliability metrics the assessment names.
- **`report.md`** - human-readable rollup: final stage statuses, the metrics table, the full
  decision lineage, and per-stage attempt/retry/rollback/MTTR breakdown.

### 3.6 Why the agents are simulated, not LLM-backed

`Agent` (`orchestrator/core/Agent.java`) is a one-method functional interface:
`StageOutcome execute(ExecutionContext ctx, StageNode self, int attemptNumber)`. The engine has no
idea what's inside an implementation - a deterministic Java class, a rules engine, or a real
Claude API call are all equally valid. This assessment ships deterministic implementations for two
concrete reasons: reviewers can run every scenario with zero API keys and zero cost, and every run
is reproducible byte-for-byte (no LLM sampling variance to explain away). The interesting
engineering problem the assessment is actually grading - the orchestration model itself
(dependency graph, gates, governance, retries, rollback, re-planning, audit) - is identical either
way. Swapping in a real LLM-backed `Agent` for, say, `RequirementsAgent` is a localized change: implement
the interface, wire it into `SdlcWorkflowDefinition`, done.

That said, the simulated agents are not just canned strings:

- `DesignApiAgent` performs **real codebase reasoning** - it walks the actual `url-shortener`
  source tree and greps for concepts named in the raw requirement, listing real impacted files
  (see the brownfield trace in `docs/SCENARIOS.md`, which finds 8 real files).
  `RequirementsAgent`'s ambiguity detection is a real (if simple) heuristic over the input text,
  not a per-scenario `if` branch - it's what actually decides whether a requirement gets the
  "documented assumptions + open questions" treatment.
- `TestingAgent` **actually shells out to `mvn test`** against the real `url-shortener` module and
  parses the real Surefire summary - the stage genuinely fails if the real suite fails.
- `GreenfieldImplementationAgent` / `BrownfieldImplementationAgent` / `AmbiguousImplementationAgent`
  verify the real, required source files exist in the shipped module (and for brownfield,
  specifically verify the atomic-UPDATE fix and the cache config are present) rather than
  asserting success unconditionally - an agent whose check fails, fails the stage for real. See
  `docs/FINAL_SUMMARY.md` -> "What 'simulated' actually means here" for the full honesty note on
  this design choice.

### 3.7 Policy guardrails

`orchestrator/policy/StandardPolicies` registers three rules applied to every stage:

- `no-hardcoded-secrets` (**BLOCK**) - any artifact value matching an obvious credential pattern
  fails the stage immediately, regardless of what the agent reported.
- `static-analysis-must-be-clean` (**BLOCK**) - any static analysis finding is treated as a hard
  change-control gate, not advisory.
- `release-requires-green-tests` (**WARN**) - documents a release-readiness hold in the audit
  trail; the actual authority for whether a release proceeds is the human approval gate, not this
  rule (WARN vs BLOCK is a deliberate distinction: some things must stop the line, others should
  just be visible to the human making the call).

`OrchestratorEngineTest.blockingPolicyViolationFailsStageWithoutRetry` proves a BLOCK violation
fails a stage even when the agent itself reported success, with no wasted retry attempts (a policy
violation is deterministic, retrying the same output changes nothing).
