package us.cubk;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PatPoolTest {
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final AtomicInteger exchanges = new AtomicInteger();

    private ObjectNode jobToken(long expiry) {
        ObjectNode jt = BearerApiClient.objectMapper.createObjectNode();
        jt.put("id", "test-user");
        jt.put("securityOauthToken", "test-token-" + exchanges.incrementAndGet());
        jt.put("refreshToken", "test-refresh-token");
        jt.put("expireTime", expiry);
        return jt;
    }

    private PatPool pool(long expiry) {
        return new PatPool(List.of("test-pat"), (pat, mid, token, type) -> jobToken(expiry), now::get);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refreshesBeforeExpiryAndAfterIdleWithStableMachineIdentity(boolean seconds) {
        PatPool pool = new PatPool(List.of("test-pat"), (pat, mid, token, type) -> {
            long expiry = now.get() + 600_000;
            return jobToken(seconds ? expiry / 1000 : expiry);
        }, now::get);
        PatPool.Acquired first = pool.acquire();
        assertNotNull(first);
        now.addAndGet(539_999);
        assertSame(first, pool.acquire());
        now.incrementAndGet();
        PatPool.Acquired renewed = pool.acquire();
        assertNotSame(first, renewed);
        assertEquals(first.session().machineId(), renewed.session().machineId());
        assertEquals(first.session().machineToken(), renewed.session().machineToken());
        assertEquals(first.session().machineType(), renewed.session().machineType());
        assertNotEquals(first.session().identity().securityOauthToken(), renewed.session().identity().securityOauthToken());
        now.addAndGet(TimeUnit.DAYS.toMillis(2));
        assertNotSame(renewed, pool.acquire());
        assertEquals(3, exchanges.get());
    }

    @Test
    void missingExpiryDoesNotCacheSessionForever() {
        PatPool pool = pool(0);
        PatPool.Acquired first = pool.acquire();
        now.addAndGet(30 * 60_000 - 1);
        assertSame(first, pool.acquire());
        now.incrementAndGet();
        assertNotSame(first, pool.acquire());
        assertEquals(2, exchanges.get());
    }

    @Test
    void shortLivedTokenDoesNotRefreshOnEveryRequest() {
        PatPool pool = new PatPool(List.of("test-pat"),
                (pat, mid, token, type) -> jobToken(now.get() + 20_000), now::get);
        PatPool.Acquired first = pool.acquire();
        assertSame(first, pool.acquire());
        now.addAndGet(10_000);
        assertNotSame(first, pool.acquire());
    }

    @Test
    void transientLoginFailureRecoversAfterCooldown() {
        AtomicInteger attempts = new AtomicInteger();
        PatPool pool = new PatPool(List.of("test-pat"), (pat, mid, token, type) -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("temporary failure");
            return jobToken(0);
        }, now::get);
        assertNull(pool.acquire());
        assertTrue(pool.hasLoginFailure());
        assertTrue(pool.allUnavailable());
        assertEquals(now.get() + 30_000, pool.earliestResetMs());
        assertNull(pool.acquire());
        assertEquals(1, attempts.get());
        now.addAndGet(30_000);
        assertNotNull(pool.acquire());
        assertFalse(pool.hasLoginFailure());
        assertEquals(2, attempts.get());
    }

    @Test
    void failedRefreshDoesNotReturnExpiredCredentials() {
        AtomicInteger attempts = new AtomicInteger();
        PatPool pool = new PatPool(List.of("test-pat"), (pat, mid, token, type) -> {
            if (attempts.incrementAndGet() == 2) throw new IOException("temporary failure");
            return jobToken(now.get() + 120_000);
        }, now::get);
        assertNotNull(pool.acquire());
        now.addAndGet(120_000);
        assertNull(pool.acquire());
        now.addAndGet(30_000);
        assertNotNull(pool.acquire());
        assertEquals(3, attempts.get());
    }

    @Test
    void badJobTokenResponseIsNotCachedAsUsable() {
        PatPool missing = new PatPool(List.of("test-pat"),
                (pat, mid, token, type) -> BearerApiClient.objectMapper.createObjectNode().put("code", 401), now::get);
        assertNull(missing.acquire());
        assertTrue(missing.hasLoginFailure());
        assertNull(pool(now.get() - 1).acquire());
    }

    @Test
    void skipsFailedPatWithinTheSameAcquisition() {
        PatPool pool = new PatPool(List.of("bad", "good"), (pat, mid, token, type) -> {
            if (pat.equals("bad")) throw new IOException("temporary failure");
            return jobToken(0);
        }, now::get);
        assertEquals("good", pool.acquire().pat());
        assertFalse(pool.allUnavailable());
        assertEquals(0, pool.earliestResetMs());
    }

    @Test
    void quotaCooldownIsPreserved() {
        PatPool pool = pool(0);
        PatPool.Acquired first = pool.acquire();
        pool.markRateLimited(first.pat(), now.get() + 90_000);
        pool.markRateLimited(first.pat(), now.get() + 30_000);
        assertNull(pool.acquire());
        assertFalse(pool.hasLoginFailure());
        now.addAndGet(90_000);
        assertSame(first, pool.acquire());
        assertEquals(1, exchanges.get());
    }

    @Test
    void concurrentRequestsShareOneRefresh() throws Exception {
        PatPool pool = pool(0);
        pool.acquire();
        now.addAndGet(TimeUnit.HOURS.toMillis(1));
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<PatPool.Acquired>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return pool.acquire();
                }));
            }
            start.countDown();
            PatPool.Acquired renewed = futures.get(0).get(5, TimeUnit.SECONDS);
            for (var future : futures) assertSame(renewed, future.get(5, TimeUnit.SECONDS));
            assertEquals(2, exchanges.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void slowLoginDoesNotBlockOtherPats() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PatPool pool = new PatPool(List.of("slow", "fast"), (pat, mid, token, type) -> {
            if (pat.equals("slow")) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return jobToken(0);
        }, now::get);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var slow = executor.submit(pool::acquire);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("fast", executor.submit(pool::acquire).get(2, TimeUnit.SECONDS).pat());
            release.countDown();
            assertEquals("slow", slow.get(5, TimeUnit.SECONDS).pat());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
