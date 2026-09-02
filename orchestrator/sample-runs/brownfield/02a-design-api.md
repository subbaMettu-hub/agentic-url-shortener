# API Design

**Goal:** The click analytics endpoint under-counts clicks under concurrent redirects to the same short code (a classic read-modify-write race), and hot redirects are hit...

## Codebase impact analysis
- `com/assessment/urlshortener/config/AppConfig.java`
- `com/assessment/urlshortener/controller/RedirectController.java`
- `com/assessment/urlshortener/controller/UrlController.java`
- `com/assessment/urlshortener/dto/AnalyticsResponse.java`
- `com/assessment/urlshortener/dto/UrlMetadataResponse.java`
- `com/assessment/urlshortener/model/ShortUrl.java`
- `com/assessment/urlshortener/repository/ShortUrlRepository.java`
- `com/assessment/urlshortener/service/UrlShortenerService.java`

## Acceptance criteria driving this design
- New/changed endpoint(s) behave as described and are covered by tests
