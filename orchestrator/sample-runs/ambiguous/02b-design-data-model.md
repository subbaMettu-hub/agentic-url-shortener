# Data Model Notes

**Goal:** Improve operational reliability of the URL shortener service within the boundaries of a single-instance prototype.

Existing entity: `ShortUrl` (shortCode, longUrl, createdAt, expiresAt, lastAccessedAt, clickCount, active, customAlias).

## Assessment
No new persistent entity or schema migration is required for this change; it composes with the existing `ShortUrl` schema.
