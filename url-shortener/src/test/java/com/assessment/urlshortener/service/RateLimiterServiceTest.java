package com.assessment.urlshortener.service;

import com.assessment.urlshortener.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterServiceTest {

    @Test
    void allowsAllTrafficWhenDisabled() {
        AppProperties props = new AppProperties();
        props.getRateLimit().setEnabled(false);
        RateLimiterService limiter = new RateLimiterService(props);

        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryConsume("client-a"));
        }
    }

    @Test
    void blocksClientAfterCapacityExhausted() {
        AppProperties props = new AppProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setCapacity(3);
        props.getRateLimit().setRefillPerMinute(0);
        RateLimiterService limiter = new RateLimiterService(props);

        assertTrue(limiter.tryConsume("client-b"));
        assertTrue(limiter.tryConsume("client-b"));
        assertTrue(limiter.tryConsume("client-b"));
        assertFalse(limiter.tryConsume("client-b"));
    }

    @Test
    void tracksClientsIndependently() {
        AppProperties props = new AppProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setCapacity(1);
        props.getRateLimit().setRefillPerMinute(0);
        RateLimiterService limiter = new RateLimiterService(props);

        assertTrue(limiter.tryConsume("client-c"));
        assertFalse(limiter.tryConsume("client-c"));
        assertTrue(limiter.tryConsume("client-d"));
    }
}
