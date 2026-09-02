# Testing Approach, Limitations, and Trade-offs

## `url-shortener` (39 tests, `mvn -f url-shortener/pom.xml test`)

| Layer | What's covered | Example |
|---|---|---|
| Unit | Pure logic, mocked collaborators (Mockito) | `Base62EncoderTest`, `UrlValidatorTest`, `UrlShortenerServiceTest`, `RateLimiterServiceTest` |
| Integration | Full Spring context, real H2 (in-memory), MockMvc through real controllers/filters | `UrlShortenerIntegrationTest` - full create -> redirect -> analytics -> delete lifecycle, validation errors, duplicate alias conflict, QR PNG generation, actuator health |
| Concurrency regression | Real Spring context, 100 concurrent redirects via a fixed thread pool + `CountDownLatch` | `ConcurrentClickCountTest` - proves the atomic-UPDATE click counter never loses an update; would flake under the naive read-modify-write approach it replaced |

**Why MockMvc integration tests instead of a full `TestRestTemplate` + running server:** faster
(no socket binding), and still exercises the real dispatcher, filters (including the rate limiter),
`@ControllerAdvice`, and JSON (de)serialization - the things actually worth an integration test.

**Why an in-memory H2 for tests, file-backed H2 for the running app:** tests need to be hermetic
and parallel-safe; the running app needs data to survive a restart. Same schema, same JPA
mappings, different `spring.datasource.url` (`src/test/resources/application.yml` overrides
`src/main/resources/application.yml` on the test classpath).

**Known limitations (see also `docs/FINAL_SUMMARY.md`):**
- No load/performance test beyond the 100-thread concurrency regression - no claim is made about
  throughput at production scale.
- No contract/API schema validation test (e.g. OpenAPI-diff) - the API surface is small enough
  that `UrlShortenerIntegrationTest` covers it directly, but this wouldn't scale to a larger API.
- Rate limiting is unit-tested in isolation (`RateLimiterServiceTest`) but not exercised through
  a live HTTP burst test - the token-bucket math is simple enough that unit coverage was judged
  sufficient for a prototype; a production rollout would want a burst test before enabling it.

## `orchestrator` (8 tests, `mvn -f orchestrator/pom.xml test`)

The engine is tested **against synthetic agents**, deliberately - `OrchestratorEngineTest` proves
the scheduler/governance mechanics in isolation, independent of whatever the real SDLC agents do:

- `executesDiamondDagRespectingDependencyOrder` - a diamond DAG genuinely executes its independent
  branches concurrently and respects join ordering.
- `retriesUpToBoundThenPasses` - a flaky agent that fails twice then succeeds is retried exactly
  as many times as needed and no more; metrics reflect exactly 2 retries.
- `exhaustsRetriesTriggersRollbackAndFailsRun` - a permanently-failing agent exhausts its bound,
  triggers rollback exactly once, and the run ends `FAILED` with downstream stages left `PENDING`
  (never executed on top of a failed dependency).
- `blockingPolicyViolationFailsStageWithoutRetry` - a BLOCK-severity policy violation fails a
  stage the agent itself reported as successful, with zero retries (policy violations are
  deterministic; retrying an unchanged output is pointless).
- `humanRejectionFailsStageWithoutRetry` - a human rejection fails the stage immediately, same
  reasoning.
- `replanInvalidatesDownstreamAndRerunsThem` - re-planning correctly invalidates every transitive
  dependent (not just the immediate one), and the plan version increments exactly once.
- `detectsCyclicGraphAtConstructionTime` - a cyclic graph is rejected before any stage runs.
- `safeStopPreventsNewStagesFromStarting` - an externally-requested safe-stop lets nothing new
  start and ends the run `ABORTED`.

This is a deliberate trade-off: testing the engine against synthetic agents means the tests are
fast (<1s total), deterministic, and pin down the *contract* the engine offers to any agent -
independent of whether that agent is the deterministic `RequirementsAgent` shipped here or a real
LLM-backed one dropped in later. The three scenario runs themselves (`docs/SCENARIOS.md`) are the
end-to-end proof that the real agents satisfy that contract in practice, and they're re-run and
inspected as part of delivering this assessment (see `orchestrator/sample-runs/`) rather than
asserted against in an automated test - their output includes a live `mvn test` invocation and
real file-system reads, which makes them integration-style demonstrations, not fast unit tests.

**Known limitations:**
- No automated test asserts on the *content* of a specific scenario's generated markdown/report -
  those are reviewed as delivered evidence (`orchestrator/sample-runs/`), not regression-tested.
  A production version of this system would want golden-file tests per scenario.
- `TestingAgent` shells out to a real `mvn test`, which means a scenario run's wall-clock time is
  dominated by the product's real test suite (~15-20s) - acceptable for a demo run every so often,
  not something you'd want in a tight inner development loop without narrowing the test scope per
  change (e.g. running only the tests relevant to the impacted files `DesignApiAgent` already
  identifies).
- The concurrency and timing-sensitive parts of the engine (parallel stage execution, retry
  backoff, MTTR calculation) are exercised by the synthetic-agent tests above but are inherently
  harder to assert on with total precision than pure functions - the tests assert on outcomes
  (call counts, final statuses, retry counts) rather than exact timings.
