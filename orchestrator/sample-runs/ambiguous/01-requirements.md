# Requirements - Revision 2

**Raw requirement:** Make the URL shortener more reliable.

**Normalized goal:** Improve operational reliability of the URL shortener service within the boundaries of a single-instance prototype.

**Ambiguous:** false

## In scope
- Bounded per-client rate limiting to protect against abusive/runaway callers
- Consistent health/readiness reporting for the service
- Input validation and structured error responses (already in place; verified, not re-built)

## Out of scope
- Multi-region failover / high availability - requires infrastructure this prototype doesn't have
- SLA-backed latency targets - no production traffic baseline to target against

## Acceptance criteria
- Rate limiting can be enabled via configuration without a code change
- A client that exceeds its quota receives HTTP 429 with a structured error body
- /actuator/health reports UP/DOWN reflecting real dependency health

## Documented assumptions
- **A1** [MEDIUM] "Reliable" is interpreted as: bounded abuse protection + accurate health signaling, not high availability or a specific latency SLA.
  - Rationale: The raw requirement gives no metric, target, or failure mode - a concrete scope has to be chosen and documented rather than guessed at implementation time.
- **A2** [LOW (accepted)] An in-memory, single-node token-bucket rate limiter is sufficient for this iteration. [REVISED: explicitly accepted as a documented MVP limitation, not silently carried forward]
  - Rationale: Design review found this assumption doesn't hold if the service is ever run with more than one instance. Rather than building distributed rate limiting under an ambiguous requirement, the scope is narrowed on purpose and the limitation is called out in docs/ARCHITECTURE.md so it's a conscious trade-off, not a hidden gap.

## Open questions
- Is this service expected to run as more than one instance in production? That would invalidate assumption A2 and require a shared rate-limit store. -> RESOLVED: out of scope for this iteration, tracked as a known limitation.
- Is there a target availability/latency SLA this should be validated against?
