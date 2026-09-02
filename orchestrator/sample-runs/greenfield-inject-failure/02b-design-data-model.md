# Data Model Notes

**Goal:** Add the ability for users to generate a QR code image for any shortened URL, accessible via a new endpoint, so links can be shared in print/offline contexts.

Existing entity: `ShortUrl` (shortCode, longUrl, createdAt, expiresAt, lastAccessedAt, clickCount, active, customAlias).

## Assessment
No new persistent entity or schema migration is required for this change; it composes with the existing `ShortUrl` schema.
