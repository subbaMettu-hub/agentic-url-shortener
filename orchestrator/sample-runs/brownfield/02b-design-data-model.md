# Data Model Notes

**Goal:** The click analytics endpoint under-counts clicks under concurrent redirects to the same short code (a classic read-modify-write race), and hot redirects are hit...

Existing entity: `ShortUrl` (shortCode, longUrl, createdAt, expiresAt, lastAccessedAt, clickCount, active, customAlias).

## Assessment
No new persistent entity or schema migration is required for this change; it composes with the existing `ShortUrl` schema.
