# Requirements - Initial normalization

**Raw requirement:** The click analytics endpoint under-counts clicks under concurrent redirects to the same short code (a classic read-modify-write race), and hot redirects are hitting the database on every request. Fix the click counting bug and add a caching layer for hot redirects to reduce DB load.

**Normalized goal:** The click analytics endpoint under-counts clicks under concurrent redirects to the same short code (a classic read-modify-write race), and hot redirects are hit...

**Ambiguous:** false

## In scope
- The specific behavior described in the raw requirement

## Out of scope

## Acceptance criteria
- New/changed endpoint(s) behave as described and are covered by tests

## Documented assumptions
- **A1** [LOW] Default values not specified in the request (formats, sizes, TTLs) follow this codebase's existing conventions rather than introducing new ones.
  - Rationale: Keeps the change consistent with the rest of the service instead of a bespoke one-off choice.

## Open questions
- None
