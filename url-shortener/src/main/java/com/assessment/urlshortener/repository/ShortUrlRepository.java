package com.assessment.urlshortener.repository;

import com.assessment.urlshortener.model.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * Atomic, database-level increment. Replaces the earlier read-modify-write pattern
     * (get click count -> increment in memory -> save) which lost updates under concurrent
     * redirects to the same short code. See brownfield scenario run for the fix rationale.
     */
    @Modifying
    @Query("UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1, s.lastAccessedAt = :accessedAt WHERE s.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode, @Param("accessedAt") java.time.Instant accessedAt);
}
