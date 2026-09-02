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

    @Transactional
    public CreateUrlResponse createShortUrl(CreateUrlRequest request) {
        if (!UrlValidator.isValid(request.longUrl())) {
            throw new InvalidUrlException("longUrl must be a valid absolute http(s) URL: " + request.longUrl());
        }

        String shortCode;
        boolean isCustom = request.customAlias() != null && !request.customAlias().isBlank();
        if (isCustom) {
            shortCode = request.customAlias();
            if (repository.existsByShortCode(shortCode)) {
                throw new AliasAlreadyExistsException(shortCode);
            }
        } else {
            shortCode = generateUniqueCode();
        }

        Instant now = Instant.now();
        Instant expiresAt = request.expiresInSeconds() != null
                ? now.plus(Duration.of(request.expiresInSeconds(), ChronoUnit.SECONDS))
                : null;

        ShortUrl entity = new ShortUrl(shortCode, request.longUrl(), now, expiresAt, isCustom);
        repository.save(entity);

        return new CreateUrlResponse(shortCode, buildShortUrl(shortCode), request.longUrl(), now, expiresAt);
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

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = codeGenerator.generate();
            if (!repository.existsByShortCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique short code after "
                + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    private String buildShortUrl(String shortCode) {
        return appProperties.getBaseUrl() + "/" + shortCode;
    }
}
