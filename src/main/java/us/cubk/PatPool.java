package us.cubk;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Round-robin PAT pool with expiring sessions and per-PAT login/rate-limit cooldowns. */
public final class PatPool {
    private static final long REFRESH_MARGIN_MS = 60_000L;
    private static final long UNKNOWN_EXPIRY_TTL_MS = 30 * 60_000L;
    private static final long LOGIN_RETRY_MS = 30_000L;

    public record Acquired(String pat, BearerBuilder.SessionContext session, BearerApiClient client) {}

    @FunctionalInterface
    interface JobTokenExchange {
        JsonNode exchange(String pat, String machineId, String machineToken, String machineType) throws Exception;
    }

    private record CachedSession(Acquired acquired, long refreshAtMs) {}

    private final Entry[] entries;
    private final JobTokenExchange exchange;
    private final LongSupplier clock;
    private int rrIndex = 0;

    public PatPool(List<String> pats) {
        this(pats, (pat, mid, token, type) -> new SignatureApiClient(mid, token, type).exchangeJobToken(pat),
                System::currentTimeMillis);
    }

    PatPool(List<String> pats, JobTokenExchange exchange, LongSupplier clock) {
        if (pats == null || pats.isEmpty()) {
            throw new IllegalArgumentException("PatPool requires at least one PAT");
        }
        List<Entry> tmp = new ArrayList<>(pats.size());
        for (String p : pats) {
            if (p == null || p.isBlank()) continue;
            tmp.add(new Entry(p));
        }
        if (tmp.isEmpty()) {
            throw new IllegalArgumentException("PatPool requires at least one non-blank PAT");
        }
        this.entries = tmp.toArray(new Entry[0]);
        this.exchange = exchange;
        this.clock = clock;
    }

    public int size() {
        return entries.length;
    }

    /** Refresh on demand, including the first request after an idle period. */
    public Acquired acquire() {
        int start;
        synchronized (this) {
            start = rrIndex;
            rrIndex = (rrIndex + 1) % entries.length;
        }
        for (int i = 0; i < entries.length; i++) {
            Entry e = entries[(start + i) % entries.length];
            if (unavailableUntil(e) > clock.getAsLong()) continue;
            // Only the entry lock is held during login, so other PATs can still be used.
            synchronized (e.initLock) {
                if (unavailableUntil(e) > clock.getAsLong()) continue;
                if (e.cached != null && clock.getAsLong() < e.cached.refreshAtMs()) {
                    return e.cached.acquired();
                }
                Acquired acquired = login(e);
                if (acquired != null) return acquired;
            }
        }
        return null;
    }

    public void openStreamLines(Acquired acquired, String url, Object body, Map<String, String> headers,
                                Consumer<String> onLine) throws Exception {
        openStreamLines(acquired, url, body, headers, onLine, null);
    }

    /** Retry authentication once, only before any upstream data has been delivered. */
    public void openStreamLines(Acquired acquired, String url, Object body, Map<String, String> headers,
                                Consumer<String> onLine, Runnable onHeartbeat) throws Exception {
        boolean[] dataDelivered = {false};
        Consumer<String> guarded = line -> {
            if (line.trim().startsWith("data:")) dataDelivered[0] = true;
            onLine.accept(line);
        };
        try {
            acquired.client().openStreamLines(url, body, headers, guarded, onHeartbeat);
        } catch (BearerApiClient.UpstreamAuthenticationException auth) {
            if (dataDelivered[0]) {
                invalidate(acquired);
                throw auth;
            }
            Acquired refreshed = refreshRejected(acquired);
            if (refreshed == null) throw auth;
            try {
                refreshed.client().openStreamLines(url, body, headers, guarded, onHeartbeat);
            } catch (BearerApiClient.UpstreamAuthenticationException retryAuth) {
                invalidate(refreshed);
                throw retryAuth;
            }
        }
    }

    private Acquired refreshRejected(Acquired rejected) {
        Entry e = find(rejected.pat());
        if (e == null) return null;
        synchronized (e.initLock) {
            // A concurrent request may already have replaced the rejected session.
            if (e.cached != null && e.cached.acquired().session() != rejected.session()) {
                return e.cached.acquired();
            }
            e.cached = null;
            if (unavailableUntil(e) > clock.getAsLong()) return null;
            return login(e);
        }
    }

    private void invalidate(Acquired rejected) {
        Entry e = find(rejected.pat());
        if (e == null) return;
        synchronized (e.initLock) {
            if (e.cached != null && e.cached.acquired().session() == rejected.session()) {
                e.cached = null;
                e.loginRetryAtMs = clock.getAsLong() + LOGIN_RETRY_MS;
            }
        }
    }

    /** Caller holds initLock; publish the session and client together after a successful exchange. */
    private Acquired login(Entry e) {
        try {
            JsonNode jt = exchange.exchange(e.pat, e.machineId, e.machineToken, e.machineType);
            if (jt == null || jt.path("id").asText("").isBlank()
                    || jt.path("securityOauthToken").asText("").isBlank()) {
                throw new IllegalStateException("jobToken response is missing session credentials");
            }
            long now = clock.getAsLong();
            long expiry = jt.path("expireTime").asLong(0);
            // The job-token endpoint may return epoch seconds or epoch milliseconds.
            if (expiry > 0 && expiry < 100_000_000_000L) expiry *= 1000;
            if (expiry > 0 && expiry <= now) {
                throw new IllegalStateException("jobToken response contains an expired session");
            }
            long refreshAt = expiry > 0
                    ? now + Math.max(1, expiry - now - Math.min(REFRESH_MARGIN_MS, (expiry - now) / 2))
                    : now + UNKNOWN_EXPIRY_TTL_MS;
            var identity = new BearerBuilder.AuthIdentity(
                    jt.path("name").asText(""), jt.path("id").asText(""), jt.path("id").asText(""),
                    "", "", "", jt.path("userType").asText("personal_standard"),
                    jt.path("securityOauthToken").asText(), jt.path("refreshToken").asText());
            var sess = BearerBuilder.newSession(identity, e.machineId, e.machineToken, e.machineType);
            Acquired acquired = new Acquired(e.pat, sess, new BearerApiClient(sess));
            e.cached = new CachedSession(acquired, refreshAt);
            e.loginRetryAtMs = 0;
            System.out.println("[pool] session renewed for " + mask(e.pat));
            return acquired;
        } catch (Exception ex) {
            e.cached = null;
            e.loginRetryAtMs = clock.getAsLong() + LOGIN_RETRY_MS;
            // Exchange errors can contain credentials in their HTTP body; log only the error type.
            System.err.println("[pool] login failed for " + mask(e.pat) + " ("
                    + ex.getClass().getSimpleName() + "); will retry after cooldown");
            return null;
        }
    }

    public void markRateLimited(String pat, long resetMs) {
        Entry e = find(pat);
        if (e == null) return;
        long until = resetMs > 0 ? resetMs : clock.getAsLong() + 30_000L;
        synchronized (e.initLock) {
            e.rateLimitedUntil = Math.max(e.rateLimitedUntil, until);
        }
        System.out.println("[pool] PAT " + mask(pat) + " cooling down until " + until);
    }

    /** Earliest retry time, or zero when at least one PAT is usable. */
    public long earliestResetMs() {
        long earliest = Long.MAX_VALUE;
        long now = clock.getAsLong();
        for (Entry e : entries) {
            long until = unavailableUntil(e);
            if (until <= now) return 0;
            earliest = Math.min(earliest, until);
        }
        return earliest;
    }

    public boolean allUnavailable() {
        return earliestResetMs() > clock.getAsLong();
    }

    public boolean hasLoginFailure() {
        long now = clock.getAsLong();
        for (Entry e : entries) {
            if (e.loginRetryAtMs > now) return true;
        }
        return false;
    }

    private static long unavailableUntil(Entry e) {
        return Math.max(e.rateLimitedUntil, e.loginRetryAtMs);
    }

    private Entry find(String pat) {
        for (Entry e : entries) {
            if (e.pat.equals(pat)) return e;
        }
        return null;
    }

    private static String mask(String pat) {
        if (pat == null || pat.length() <= 8) return "***";
        return pat.substring(0, 4) + "..." + pat.substring(pat.length() - 4);
    }

    private static final class Entry {
        final String pat;
        final String machineId = java.util.UUID.randomUUID().toString();
        final String machineToken = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID())
                        .substring(0, 50).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        final String machineType = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        final Object initLock = new Object();
        volatile CachedSession cached;
        volatile long loginRetryAtMs;
        volatile long rateLimitedUntil;

        Entry(String pat) {
            this.pat = pat;
        }
    }
}
