package com.assessment.urlshortener.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
@EnableCaching
public class AppConfig {

    public static final String SHORT_URL_CACHE = "shortUrlLookup";

    /**
     * In-process cache in front of short-code -> long-URL lookups. Redirects are by far the
     * highest-QPS path in a URL shortener and are read-mostly, so caching here removes DB load
     * from the hot path without touching write-side consistency (writes evict/replace the key).
     * Bounded size + short TTL keep staleness (e.g. after a delete) low without needing a
     * distributed cache for this single-instance prototype.
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(SHORT_URL_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(5, TimeUnit.MINUTES));
        return manager;
    }
}
