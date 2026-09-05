package com.assessment.urlshortener.service;

import com.assessment.urlshortener.config.AppProperties;
import com.assessment.urlshortener.dto.CreateUrlRequest;
import com.assessment.urlshortener.dto.CreateUrlResponse;
import com.assessment.urlshortener.exception.AliasAlreadyExistsException;
import com.assessment.urlshortener.exception.InvalidUrlException;
import com.assessment.urlshortener.exception.UrlGoneException;
import com.assessment.urlshortener.exception.UrlNotFoundException;
import com.assessment.urlshortener.model.ShortUrl;
import com.assessment.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    @Mock
    private ShortUrlRepository repository;

    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setBaseUrl("http://localhost:8080");
        ShortCodeGenerator generator = new ShortCodeGenerator(props);
        service = new UrlShortenerService(repository, generator, props);
    }

    @Test
    void createShortUrl_rejectsInvalidLongUrl() {
        CreateUrlRequest request = new CreateUrlRequest("javascript:alert(1)", null, null);
        assertThrows(InvalidUrlException.class, () -> service.createShortUrl(request));
        verifyNoInteractions(repository);
    }

    @Test
    void createShortUrl_usesCustomAliasWhenProvided() {
        when(repository.existsByShortCode("my-alias")).thenReturn(false);
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "my-alias", null);

        CreateUrlResponse response = service.createShortUrl(request);

        assertEquals("my-alias", response.shortCode());
        assertEquals("http://localhost:8080/my-alias", response.shortUrl());
        verify(repository).save(any(ShortUrl.class));
    }

    @Test
    void createShortUrl_rejectsDuplicateCustomAlias() {
        when(repository.existsByShortCode("taken")).thenReturn(true);
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "taken", null);

        assertThrows(AliasAlreadyExistsException.class, () -> service.createShortUrl(request));
        verify(repository, never()).save(any());
    }

    @Test
    void createShortUrl_generatesCodeAndRetriesOnCollision() {
        when(repository.existsByShortCode(anyString())).thenReturn(true, false);
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, null);

        CreateUrlResponse response = service.createShortUrl(request);

        assertNotNull(response.shortCode());
        verify(repository, times(2)).existsByShortCode(anyString());
        verify(repository).save(any(ShortUrl.class));
    }

    @Test
    void createShortUrl_retriesWhenConcurrentInsertWinsRaceOnGeneratedCode() {
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.save(any(ShortUrl.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, null);

        CreateUrlResponse response = service.createShortUrl(request);

        assertNotNull(response.shortCode());
        verify(repository, times(2)).save(any(ShortUrl.class));
    }

    @Test
    void createShortUrl_rejectsCustomAliasWhenConcurrentInsertWinsRace() {
        when(repository.existsByShortCode("race-alias")).thenReturn(false);
        when(repository.save(any(ShortUrl.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "race-alias", null);

        assertThrows(AliasAlreadyExistsException.class, () -> service.createShortUrl(request));
    }

    @Test
    void createShortUrl_setsExpiryWhenRequested() {
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, 3600L);

        Instant before = Instant.now();
        CreateUrlResponse response = service.createShortUrl(request);
        Instant expectedFloor = before.plus(3600, ChronoUnit.SECONDS);

        assertNotNull(response.expiresAt());
        assertFalse(response.expiresAt().isBefore(expectedFloor.minusSeconds(2)));
    }

    @Test
    void resolveForRedirect_throwsNotFoundForUnknownCode() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());
        assertThrows(UrlNotFoundException.class, () -> service.resolveForRedirect("missing"));
    }

    @Test
    void resolveForRedirect_throwsGoneForExpiredUrl() {
        ShortUrl expired = new ShortUrl("abc123", "https://example.com", Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60), false);
        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(expired));

        assertThrows(UrlGoneException.class, () -> service.resolveForRedirect("abc123"));
    }

    @Test
    void resolveForRedirect_returnsActiveNonExpiredUrl() {
        ShortUrl active = new ShortUrl("abc123", "https://example.com", Instant.now(), null, false);
        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(active));

        ShortUrl result = service.resolveForRedirect("abc123");

        assertEquals("https://example.com", result.getLongUrl());
    }

    @Test
    void deactivate_marksUrlInactive() {
        ShortUrl active = new ShortUrl("abc123", "https://example.com", Instant.now(), null, false);
        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(active));

        service.deactivate("abc123");

        assertFalse(active.isActive());
        verify(repository).save(active);
    }
}
