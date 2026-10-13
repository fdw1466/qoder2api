package us.cubk;

import java.util.ArrayList;
import java.util.List;

/**
 * Pool of Qoder Personal Access Tokens, each lazily upgraded into a {@link BearerBuilder.SessionContext}.
 *
 * <p>Replaces the old single-PAT model where {@code OpenAiBridge} held one {@code sess} for its whole
 * lifetime. Here every request {@link #acquire()}s a usable session (round-robin, skipping PATs that
 * are cooling down after an upstream rate-limit, or that permanently failed to exchange a job token).
 *
 * <p>Selection is stateless round-robin: the same PAT may be handed out to several concurrent requests
 * because the upstream allows concurrent use of one account — only the quota is shared. Rate-limit
 * feedback arrives via {@link #markRateLimited(String, long)} which cools that PAT until resetMs.
 */
public final class PatPool {

    /** A session picked from the pool, paired with the PAT it belongs to (for rate-limit feedback). */
    public record Acquired(String pat, BearerBuilder.SessionContext session, BearerApiClient client) {}

    private final Entry[] entries;
    private int rrIndex = 0;

    public PatPool(List<String> pats) {
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
    }

    public int size() {
        return entries.length;
    }

    /**
     * Pick a usable session. Lazily upgrades the chosen PAT to a session on first use (double-checked
     * under {@code initLock}). Returns {@code null} only if every PAT is cooling down or permanently
     * failed.
     */
    public Acquired acquire() {
        synchronized (this) {
            int n = entries.length;
            // First pass: find one that is immediately usable (not failed, not cooling).
            for (int i = 0; i < n; i++) {
                Entry e = entries[(rrIndex + i) % n];
                if (e.failed) continue;
                if (e.rateLimitedUntil > 0 && e.rateLimitedUntil > System.currentTimeMillis()) continue;
                rrIndex = (rrIndex + i + 1) % n;
                Entry chosen = e;
                // release the pool lock before (possibly) doing network init
                return initAndReturn(chosen);
            }
        }
        return null;
    }

    private Acquired initAndReturn(Entry e) {
        BearerBuilder.SessionContext sess = e.session;
        if (sess == null) {
            synchronized (e.initLock) {
                sess = e.session;
                if (sess == null) {
                    try {
                        sess = upgrade(e.pat);
                        e.session = sess;
                        e.client = new BearerApiClient(sess);
                    } catch (Exception ex) {
                        System.err.println("[pool] PAT upgrade failed for " + mask(e.pat) + ": " + ex.getMessage());
                        e.failed = true;
                        return null;
                    }
                }
            }
        }
        return new Acquired(e.pat, sess, e.client);
    }

    /** Mark a PAT as rate-limited until {@code resetMs} (epoch millis, 0 = unknown → short cooldown). */
    public void markRateLimited(String pat, long resetMs) {
        Entry e = find(pat);
        if (e == null) return;
        long until = resetMs > 0 ? resetMs : (System.currentTimeMillis() + 30_000L);
        // keep the later of the current cooldown and the new one (a concurrent 429 may report an earlier reset)
        if (until > e.rateLimitedUntil) {
            e.rateLimitedUntil = until;
        }
        System.out.println("[pool] PAT " + mask(pat) + " cooling down until " + until);
    }

    /**
     * Earliest moment any PAT becomes usable again, for building a 429 response. Returns 0 if at least
     * one PAT is currently usable.
     */
    public long earliestResetMs() {
        long earliest = Long.MAX_VALUE;
        boolean anyUsable = false;
        for (Entry e : entries) {
            if (e.failed) continue;
            if (e.rateLimitedUntil <= System.currentTimeMillis()) {
                anyUsable = true;
                break;
            }
            if (e.rateLimitedUntil < earliest) earliest = e.rateLimitedUntil;
        }
        return anyUsable ? 0 : (earliest == Long.MAX_VALUE ? 0 : earliest);
    }

    /** True if every PAT is either permanently failed or cooling down right now. */
    public boolean allUnavailable() {
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            if (e.failed) continue;
            if (e.rateLimitedUntil <= now) return false;
        }
        return true;
    }

    private Entry find(String pat) {
        for (Entry e : entries) {
            if (e.pat.equals(pat)) return e;
        }
        return null;
    }

    /** Upgrade a PAT to a live session: exchange job token then build the COSY session context. */
    private static BearerBuilder.SessionContext upgrade(String pat) throws Exception {
        String mid = java.util.UUID.randomUUID().toString();
        String mtoken = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID())
                        .substring(0, 50).getBytes());
        String mtype = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        var sigClient = new SignatureApiClient(mid, mtoken, mtype);
        com.fasterxml.jackson.databind.JsonNode jt = sigClient.exchangeJobToken(pat);
        System.out.println("[pool] session for " + jt.path("name").asText() + " (" + jt.path("id").asText() + ")");
        var identity = new BearerBuilder.AuthIdentity(
                jt.path("name").asText(""),
                jt.path("id").asText(""),
                jt.path("id").asText(""),
                "", "", "",
                jt.path("userType").asText("personal_standard"),
                jt.path("securityOauthToken").asText(),
                jt.path("refreshToken").asText());
        return BearerBuilder.newSession(identity, mid, mtoken, mtype);
    }

    private static String mask(String pat) {
        if (pat == null || pat.length() <= 8) return "***";
        return pat.substring(0, 4) + "..." + pat.substring(pat.length() - 4);
    }

    private static final class Entry {
        final String pat;
        final Object initLock = new Object();
        volatile BearerBuilder.SessionContext session;
        volatile BearerApiClient client;
        volatile boolean failed;
        volatile long rateLimitedUntil;

        Entry(String pat) {
            this.pat = pat;
        }
    }
}
