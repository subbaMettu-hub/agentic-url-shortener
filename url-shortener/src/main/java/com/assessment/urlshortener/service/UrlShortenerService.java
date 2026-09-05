package com.assessment.urlshortener.service;

import com.assessment.urlshortener.config.AppConfig;
import com.assessment.urlshortener.config.AppProperties;
import com.assessment.urlshortener.dto.AnalyticsResponse;
import com.assessment.urlshortener.dto.CreateUrlRequest;
import com.assessment.urlshortener.dto.CreateUrlResponse;
import com.assessment.urlshortener.dto.UrlMetadataResponse;
import com.assessment.urlshortener.exception.AliasAlreadyExistsException;
import com.assessment.urlshortener.exception.InvalidUrlException;
import com.assessment.urlshortener.exception.UrlGoneException;
import com.assessment.urlshortener.exception.UrlNotFoundException;
import com.assessment.urlshortener.model.ShortUrl;
import com.assessment.urlshortener.repository.ShortUrlRepository;
import com.assessment.urlshortener.util.UrlValidator;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class UrlShortenerService {

    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator codeGenerator;
    private final AppProperties appProperties;

    public UrlShortenerService(ShortUrlRepository repository, ShortCodeGenerator codeGenerator, AppProperties appProperties) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.appProperties = appProperties;
    }

    /**
     * Intentionally not wrapped in a single {@code @Transactional}: the existsByShortCode
     * pre-check and the save below are two separate round trips regardless of transaction
     * boundaries, so a concurrent request can still win the race between them. Leaving each
     * {@code repository.save} call as its own (Spring Data JPA default) transaction means a
     * failed attempt here can't poison a later retry's persistence context, and the unique
     * constraint on shortCode is the actual correctness guarantee - the pre-check is just an
     * optimization to avoid paying for a constraint-violation round trip on the common path.
     */
    public CreateUrlResponse createShortUrl(CreateUrlRequest request) {
        if (!UrlValidator.isValid(request.longUrl())) {
            throw new InvalidUrlException("longUrl must be a valid absolute http(s) URL: " + request.longUrl());
        }

        Instant now = Instant.now();
        Instant expiresAt = request.expiresInSeconds() != null
                ? now.plus(Duration.of(request.expiresInSeconds(), ChronoUnit.SECONDS))
                : null;

        boolean isCustom = request.customAlias() != null && !request.customAlias().isBlank();
        if (isCustom) {
            String shortCode = request.customAlias();
            if (repository.existsByShortCode(shortCode)) {
                throw new AliasAlreadyExistsException(shortCode);
            }
            try {
                repository.save(new ShortUrl(shortCode, request.longUrl(), now, expiresAt, true));
            } catch (DataIntegrityViolationException e) {
                throw new AliasAlreadyExistsException(shortCode);
            }
            return new CreateUrlResponse(shortCode, buildShortUrl(shortCode), request.longUrl(), now, expiresAt);
        }

        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = codeGenerator.generate();
            if (repository.existsByShortCode(candidate)) {
                continue;
            }
            try {
                repository.save(new ShortUrl(candidate, request.longUrl(), now, expiresAt, false));
                return new CreateUrlResponse(candidate, buildShortUrl(candidate), request.longUrl(), now, expiresAt);
            } catch (DataIntegrityViolationException e) {
                // Lost a race with a concurrent insert of the same code between the
                // existsByShortCode check and this save; try another candidate.
            }
        }
        throw new IllegalStateException("Failed to generate a unique short code after "
                + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    /**
     * Cached because redirects are the highest-traffic read path. Cache is keyed by short code
     * and evicted on delete so a removed link never resolves from a stale cache entry.
     */
    @Cacheable(value = AppConfig.SHORT_URL_CACHE, key = "#shortCode", unless = "#result == null")
    public ShortUrl resolveForRedirect(String shortCode) {
        ShortUrl url = repository.findByShortCode(shortCode)
                .filter(ShortUrl::isActive)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        if (url.isExpired(Instant.now())) {
            throw new UrlGoneException(shortCode);
        }
        return url;
    }

    @Transactional
    public void recordClick(String shortCode) {
        repository.incrementClickCount(shortCode, Instant.now());
    }

    public UrlMetadataResponse getMetadata(String shortCode) {
        ShortUrl url = findActiveOrThrow(shortCode);
        return new UrlMetadataResponse(
                url.getShortCode(), buildShortUrl(url.getShortCode()), url.getLongUrl(),
                url.getCreatedAt(), url.getExpiresAt(), url.getLastAccessedAt(),
                url.getClickCount(), url.isActive(), url.isCustomAlias());
    }

    public AnalyticsResponse getAnalytics(String shortCode) {
        ShortUrl url = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        long daysActive = Math.max(1, Duration.between(url.getCreatedAt(), Instant.now()).toDays());
        double avgPerDay = url.getClickCount() / (double) daysActive;
        return new AnalyticsResponse(
                url.getShortCode(), url.getClickCount(), url.getCreatedAt(), url.getLastAccessedAt(),
                Math.round(avgPerDay * 100.0) / 100.0, url.isActive(), url.isExpired(Instant.now()));
    }

    @Transactional
    @CacheEvict(value = AppConfig.SHORT_URL_CACHE, key = "#shortCode")
    public void deactivate(String shortCode) {
        ShortUrl url = findActiveOrThrow(shortCode);
        url.setActive(false);
        repository.save(url);
    }

    private ShortUrl findActiveOrThrow(String shortCode) {
        return repository.findByShortCode(shortCode)
                .filter(ShortUrl::isActive)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
    }

    private String buildShortUrl(String shortCode) {
        return appProperties.getBaseUrl() + "/" + shortCode;
    }
}
