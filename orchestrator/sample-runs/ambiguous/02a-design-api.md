# API Design

**Goal:** Improve operational reliability of the URL shortener service within the boundaries of a single-instance prototype.

## Codebase impact analysis
No existing files matched requirement keywords - this is additive/new surface area.

## Acceptance criteria driving this design
- Rate limiting can be enabled via configuration without a code change
- A client that exceeds its quota receives HTTP 429 with a structured error body
- /actuator/health reports UP/DOWN reflecting real dependency health
