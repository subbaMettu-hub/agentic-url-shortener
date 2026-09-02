# Documentation - Ambiguous: "make the URL shortener more reliable"

**Summary:** Improve operational reliability of the URL shortener service within the boundaries of a single-instance prototype.

**Files impacted:** none (additive)

**Acceptance criteria:**
- Rate limiting can be enabled via configuration without a code change
- A client that exceeds its quota receives HTTP 429 with a structured error body
- /actuator/health reports UP/DOWN reflecting real dependency health
