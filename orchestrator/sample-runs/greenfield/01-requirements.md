# Requirements - Initial normalization

**Raw requirement:** Add the ability for users to generate a QR code image for any shortened URL, accessible via a new endpoint, so links can be shared in print/offline contexts.

**Normalized goal:** Add the ability for users to generate a QR code image for any shortened URL, accessible via a new endpoint, so links can be shared in print/offline contexts.

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
