package com.assessment.urlshortener.service;

import com.assessment.urlshortener.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory token-bucket rate limiter, keyed per client (IP by default).
 *
 * Scope note: adequate for a single-instance prototype. A multi-instance deployment would need
 * a shared store (e.g. Redis) so limits are enforced across instances rather than per-node -
 * called out explicitly in docs/ARCHITECTURE.md as a known limitation.
 */
@Service
public class RateLimiterService {

    private final AppProperties.RateLimit config;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiterService(AppProperties appProperties) {
        this.config = appProperties.getRateLimit();
    }

    public boolean tryConsume(String clientKey) {
        if (!config.isEnabled()) {
            return true;
        }
        Bucket bucket = buckets.computeIfAbsent(clientKey, k -> new Bucket(config.getCapacity()));
        return bucket.tryConsume(config.getCapacity(), config.getRefillPerMinute());
    }

    private static final class Bucket {
        private final AtomicLong tokens;
        private volatile long lastRefillEpochMs;

        Bucket(int capacity) {
            this.tokens = new AtomicLong(capacity);
            this.lastRefillEpochMs = System.currentTimeMillis();
        }

        synchronized boolean tryConsume(int capacity, int refillPerMinute) {
            refill(capacity, refillPerMinute);
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }

        private void refill(int capacity, int refillPerMinute) {
            long now = System.currentTimeMillis();
            long elapsedMs = now - lastRefillEpochMs;
            double tokensToAdd = (elapsedMs / 60_000.0) * refillPerMinute;
            if (tokensToAdd >= 1) {
                long newValue = Math.min(capacity, tokens.get() + (long) tokensToAdd);
                tokens.set(newValue);
                lastRefillEpochMs = now;
            }
        }
    }
}
