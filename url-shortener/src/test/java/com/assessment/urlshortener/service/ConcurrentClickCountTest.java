package com.assessment.urlshortener.service;

import com.assessment.urlshortener.dto.AnalyticsResponse;
import com.assessment.urlshortener.dto.CreateUrlRequest;
import com.assessment.urlshortener.dto.CreateUrlResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the concurrency defect the brownfield scenario addresses: a naive
 * read-modify-write click counter (`count = get(); count++; save(count)`) loses updates when
 * multiple redirects for the same short code land concurrently, because two threads can read
 * the same starting value before either writes back. {@link ShortUrlRepository#incrementClickCount}
 * uses a single atomic {@code UPDATE ... SET clickCount = clickCount + 1} statement instead, so
 * every increment is durable regardless of interleaving. This test fails under the old
 * read-modify-write implementation and passes under the atomic one.
 */
@SpringBootTest
class ConcurrentClickCountTest {

    private static final int CONCURRENT_CLICKS = 100;

    @Autowired
    private UrlShortenerService urlShortenerService;

    @Test
    void concurrentRedirectsAllIncrementClickCount() throws InterruptedException {
        CreateUrlResponse created = urlShortenerService.createShortUrl(
                new CreateUrlRequest("https://example.com/concurrency-target", null, null));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_CLICKS);

        for (int i = 0; i < CONCURRENT_CLICKS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    urlShortenerService.recordClick(created.shortCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        assertEquals(true, doneLatch.await(10, TimeUnit.SECONDS), "concurrent clicks did not finish in time");
        pool.shutdown();

        AnalyticsResponse analytics = urlShortenerService.getAnalytics(created.shortCode());
        assertEquals(CONCURRENT_CLICKS, analytics.totalClicks(),
                "lost updates detected: atomic increment should never drop a concurrent click");
    }
}
