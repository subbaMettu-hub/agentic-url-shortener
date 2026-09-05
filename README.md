# Agentic URL Shortener

Interview assessment: *Build an Agentic Software Engineering System - URL Shortener*.

This repository contains two things:

1. **`url-shortener/`** - a working URL shortener service (Spring Boot / Java 17): create, redirect,
   analytics, QR codes, rate limiting, caching, health checks.
2. **`orchestrator/`** - a standalone, dependency-graph SDLC orchestration engine (the assessment's
   "critical differentiator"). It coordinates agent stages - requirements, design, implementation,
   static analysis, testing, documentation, release readiness - with parallel execution and
   synchronization, bounded retries, rollback, human approval gates, policy guardrails, audit
   logging, reliability metrics, and dynamic re-planning. Most stages are deterministic (and one,
   `RequirementsAgent`, calls the real Claude API when `ANTHROPIC_API_KEY` is set - see below -
   falling back to a deterministic heuristic otherwise). It is demonstrated against three scenarios
   for the URL shortener: **greenfield**, **brownfield**, and **ambiguous**.

Read `docs/ARCHITECTURE.md` for how the two fit together and why they're built this way,
`docs/SCENARIOS.md` for a walkthrough of the three required scenarios with real execution traces,
`docs/TESTING.md` for the testing approach/limitations/trade-offs, and `docs/FINAL_SUMMARY.md` for
the plan, rationale, risks, assumptions, and limitations required by the assessment.

## Prerequisites

- **JDK 17+**. If you don't have one, this repo works fine with the JDK bundled inside IntelliJ IDEA
  (JetBrains Runtime): `C:\Program Files\JetBrains\IntelliJ IDEA <version>\jbr`.
- **Maven 3.9+**. If it's not installed, download the "Binary zip archive" from
  https://maven.apache.org/download.cgi, unzip it anywhere, and put its `bin/` directory on your PATH.
- Internet access on first build (to download Maven Central dependencies).

```powershell
# Example one-time setup on Windows if you only have the JetBrains-bundled JDK and no Maven:
$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
$env:Path = "C:\path\to\apache-maven-3.9.9\bin;$env:JAVA_HOME\bin;$env:Path"
```

## Quick start

From the repository root:

```powershell
# 1. Build and test everything (product + orchestrator)
mvn -f url-shortener/pom.xml test
mvn -f orchestrator/pom.xml test

# 2. Run the URL shortener service (listens on :8080)
mvn -f url-shortener/pom.xml spring-boot:run
```

In another terminal, exercise the API:

```powershell
# Create a short URL
curl -X POST http://localhost:8080/api/v1/urls -H "Content-Type: application/json" `
  -d '{"longUrl":"https://example.com/some/long/path"}'
# -> {"shortCode":"Ab3xQ9","shortUrl":"http://localhost:8080/Ab3xQ9",...}

curl -i http://localhost:8080/Ab3xQ9                       # 302 redirect + increments click count
curl http://localhost:8080/api/v1/urls/Ab3xQ9/analytics    # click analytics
curl http://localhost:8080/api/v1/urls/Ab3xQ9/qrcode -o qr.png   # QR code PNG
curl http://localhost:8080/actuator/health                 # health check
```

```powershell
# 3. Run the orchestrator against all three scenarios
mvn -f orchestrator/pom.xml compile exec:java "-Dexec.args=--scenario=all"
```

### Optional: real LLM reasoning for the requirements stage

By default `RequirementsAgent` uses a deterministic heuristic - no API key needed, byte-for-byte
reproducible. Set `ANTHROPIC_API_KEY` before running a scenario to have it call the real Claude API
instead; on any failure (missing key, network error, malformed response) it falls back to the
heuristic automatically, so a run always completes either way. `01-requirements.md` states which
path actually ran (`**Analysis method:**`).

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
mvn -f orchestrator/pom.xml compile exec:java "-Dexec.args=--scenario=ambiguous"
```

Each run writes its full artifact set - normalized requirements, design docs, static analysis
report, real test output, documentation, release readiness report, `audit.jsonl`, `metrics.json`,
and a rolled-up `report.md` - under `orchestrator/runs/<scenario>/<runId>/`. A committed snapshot
of one clean run per scenario lives in `orchestrator/sample-runs/` so you can read the evidence
without running anything.

### Orchestrator CLI options

```
--scenario=greenfield|brownfield|ambiguous|all   (default: all)
--interactive                                     require a real y/N console approval at Release Readiness
--inject-failure=<STAGE_ID>                       force a stage to always fail, to see retry
                                                   exhaustion -> rollback -> safe-stop for real
--timeout-seconds=N                               safe-stop the run if it exceeds this budget (default 180)
--project-root=<path>                             override auto-detected project root
```

Examples:

```powershell
# Real human-in-the-loop approval at the release gate
mvn -f orchestrator/pom.xml compile exec:java "-Dexec.args=--scenario=brownfield --interactive"

# See bounded retries exhaust, rollback fire, and a governed safe-stop
mvn -f orchestrator/pom.xml compile exec:java "-Dexec.args=--scenario=greenfield --inject-failure=STATIC_ANALYSIS"
```

## Project structure

```
agentic-url-shortener/
├── url-shortener/            Spring Boot product: APIs, analytics, reliability features, tests
├── orchestrator/             Standalone SDLC orchestration engine + 3 scenario definitions, tests
│   ├── core/                 DAG engine: WorkflowGraph, OrchestratorEngine, policy, metrics, audit
│   ├── agents/                Agent implementations for each SDLC stage (deterministic, except
│   │                            RequirementsAgent's optional real Claude API path)
│   ├── llm/                    ReasoningProvider abstraction + ClaudeReasoningProvider
│   ├── workflow/              The SDLC workflow DAG definition shared by all scenarios
│   ├── scenarios/              greenfield / brownfield / ambiguous scenario definitions
│   ├── cli/                    OrchestratorCli entry point
│   └── sample-runs/            Committed example run output (evidence, not regenerated)
└── docs/
    ├── ARCHITECTURE.md
    ├── SCENARIOS.md
    ├── TESTING.md
    ├── FINAL_SUMMARY.md
    └── CHANGELOG.md           Appended to by the orchestrator's Documentation stage
```
