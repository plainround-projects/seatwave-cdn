package io.plainround.seatwave.cdn.edge;

import io.plainround.seatwave.cdn.http.CacheControl;
import io.plainround.seatwave.cdn.http.Reply;
import io.plainround.seatwave.cdn.http.Upstream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A model of one shared HTTP cache tier: a CDN edge, an origin shield, or a caching proxy. It
 * stores what RFC 9111 lets a shared cache store, serves it while fresh, revalidates it with {@code
 * If-None-Match} when stale, may serve it stale while it refreshes, and can be purged.
 *
 * <p>Time comes only from the injected {@link InstantSource}, so a run on a stepped clock is
 * exactly repeatable. Not thread-safe: one model is driven by one thread (the demos and the test);
 * production caches are NGINX, Varnish or a CDN, configured as Section 8 shows.
 *
 * <p>What it never does, whatever the origin says: store a response to a request with credentials,
 * store anything under a never-store prefix, store a response that sets a cookie or varies.
 */
public final class EdgeCache implements Upstream {

  private static final String KEY_SEPARATOR = " "; // host and path cannot contain a space
  private static final String API_PREFIX = "/v1/";
  private static final int HTTP_OK = 200;
  private static final int HTTP_NOT_MODIFIED = 304;

  private final Settings settings;
  private final Upstream upstream;
  private final InstantSource clock;
  private final Map<String, StoredEntry> entries = new HashMap<>();
  private final Counters counters = new Counters();

  /** Builds a tier in front of {@code upstream}. */
  public EdgeCache(Settings settings, Upstream upstream, InstantSource clock) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.upstream = Objects.requireNonNull(upstream, "upstream");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Answers a request from the cache or from upstream, and labels the answer with {@code
   * x-cache-status} (as NGINX's {@code $upstream_cache_status}): HIT, MISS, EXPIRED, REVALIDATED,
   * STALE, COALESCED or BYPASS.
   */
  @Override
  public Reply fetch(Request request) {
    Objects.requireNonNull(request, "request");
    counters.requests++;
    if (mustBypass(request)) {
      counters.bypassed++;
      // No Age on a bypass: the answer did not come from this cache.
      return sendUpstream(request).withHeader("x-cache-status", "BYPASS");
    }
    String key = cacheKey(request);
    Instant now = clock.instant();
    StoredEntry stored = entries.get(key);
    if (stored == null) {
      return fillFromUpstream(key, request, now, "MISS");
    }
    if (now.isBefore(stored.availableAt())) {
      // Another request is already fetching this key. With coalescing it waits for that fetch
      // (NGINX proxy_cache_lock); without, it goes upstream too: the herd after a purge.
      if (settings.coalescesMisses()) {
        counters.coalesced++;
        return serve(stored, request, now, "COALESCED");
      }
      counters.misses++;
      return label(sendUpstream(request), "MISS", Duration.ZERO);
    }
    Duration age = stored.ageAt(now);
    if (age.compareTo(stored.lifetime()) < 0) {
      counters.hits++;
      return serve(stored, request, now, "HIT");
    }
    if (age.compareTo(stored.lifetime().plus(stored.staleWindow())) < 0) {
      // stale-while-revalidate: this fan gets the old copy at once; one refresh runs for the
      // next fans. (The model refreshes before returning; a real cache does it in the background.)
      counters.staleServed++;
      Reply staleCopy = serve(stored, request, now, "STALE");
      revalidate(key, request, stored, now, Refresh.BACKGROUND);
      return staleCopy;
    }
    return revalidate(key, request, stored, now, Refresh.ON_REQUEST);
  }

  /** Removes every stored entry whose path starts with {@code pathPrefix}; returns how many. */
  public int purge(String pathPrefix) {
    Objects.requireNonNull(pathPrefix, "pathPrefix");
    int before = entries.size();
    entries.keySet().removeIf(key -> pathOfKey(key).startsWith(pathPrefix));
    return before - entries.size();
  }

  /** The counts so far, as an immutable snapshot. */
  public Stats stats() {
    return new Stats(
        settings.name(),
        counters.requests,
        counters.hits,
        counters.misses,
        counters.revalidated,
        counters.staleServed,
        counters.backgroundRefreshes,
        counters.coalesced,
        counters.bypassed,
        counters.upstreamRequests,
        counters.upstreamBytes,
        entries.size());
  }

  private boolean mustBypass(Request request) {
    // A request with credentials is answered for one person only; RFC 9111 (Section 3.5) lets a
    // shared cache store it only if the origin says so, and SeatWave never does.
    return !request.method().equals("GET")
        || request.header("authorization").isPresent()
        || settings.neverStorePrefixes().stream().anyMatch(request.path()::startsWith);
  }

  private Reply fillFromUpstream(String key, Request request, Instant now, String status) {
    counters.misses++;
    Reply reply = sendUpstream(request);
    storableLifetime(reply)
        .ifPresent(lifetime ->
            entries.put(
                key,
                new StoredEntry(
                    reply,
                    now,
                    upstreamAge(reply),
                    now.plus(settings.fillTime()),
                    lifetime,
                    reply.cacheControl().staleWhileRevalidate())));
    return label(reply, status, upstreamAge(reply));
  }

  private Reply revalidate(
      String key, Request request, StoredEntry stored, Instant now, Refresh refresh) {
    Optional<String> entityTag = stored.reply().header("etag");
    if (entityTag.isEmpty()) {
      entries.remove(key);
      return fillFromUpstream(key, request, now, "EXPIRED");
    }
    Reply answer = sendUpstream(request.withHeader("if-none-match", entityTag.get()));
    if (answer.status() != HTTP_NOT_MODIFIED) {
      entries.remove(key);
      counters.misses++;
      storableLifetime(answer)
          .ifPresent(lifetime ->
              entries.put(
                  key,
                  new StoredEntry(
                      answer,
                      now,
                      upstreamAge(answer),
                      now,
                      lifetime,
                      answer.cacheControl().staleWhileRevalidate())));
      return label(answer, "EXPIRED", upstreamAge(answer));
    }
    // 304 Not Modified: the stored body is still right; only its clock restarts (RFC 9111, 4.3.4).
    if (refresh == Refresh.BACKGROUND) {
      counters.backgroundRefreshes++;
    } else {
      counters.revalidated++;
    }
    StoredEntry refreshed =
        new StoredEntry(
            stored.reply(),
            now,
            upstreamAge(answer),
            now,
            answer.cacheControl().sharedLifetime().orElse(stored.lifetime()),
            stored.staleWindow());
    entries.put(key, refreshed);
    return serve(refreshed, request, now, "REVALIDATED");
  }

  private Optional<Duration> storableLifetime(Reply reply) {
    CacheControl directives = reply.cacheControl();
    boolean isStorable =
        reply.status() == HTTP_OK
            && !directives.forbidsSharedStorage()
            && reply.header("set-cookie").isEmpty()
            // This model keys on host and path only, so it refuses any response that varies.
            && reply.header("vary").isEmpty();
    // No explicit lifetime: RFC 9111 would allow a heuristic one; this cache fails safe and
    // stores nothing it was not told it may keep.
    return isStorable ? directives.sharedLifetime() : Optional.empty();
  }

  private Duration upstreamAge(Reply reply) {
    if (!settings.speaksAge()) {
      return Duration.ZERO;
    }
    return reply
        .header("age")
        .filter(value -> !value.isEmpty() && value.chars().allMatch(Character::isDigit))
        .map(value -> Duration.ofSeconds(Long.parseLong(value)))
        .orElse(Duration.ZERO);
  }

  private Reply sendUpstream(Request request) {
    counters.upstreamRequests++;
    Reply reply = upstream.fetch(request);
    counters.upstreamBytes += reply.bodyLength();
    return reply;
  }

  private Reply serve(StoredEntry stored, Request request, Instant now, String status) {
    Reply copy = stored.reply();
    Optional<String> entityTag = copy.header("etag");
    if (entityTag.isPresent() && request.header("if-none-match").equals(entityTag)) {
      // A tier below asks "is my copy still right?": answer from this cache, headers only.
      copy = new Reply(HTTP_NOT_MODIFIED, copy.headers(), ByteBuffer.allocate(0));
    }
    return label(copy, status, stored.ageAt(now));
  }

  private Reply label(Reply reply, String status, Duration age) {
    Reply labeled = reply.withHeader("x-cache-status", status);
    // RFC 9111 requires a cache to send Age; NGINX 1.30.6 neither sends nor reads it.
    return settings.speaksAge()
        ? labeled.withHeader("age", Long.toString(age.toSeconds()))
        : labeled;
  }

  private static String cacheKey(Request request) {
    return request.host() + KEY_SEPARATOR + request.pathAndQuery();
  }

  private static String pathOfKey(String key) {
    return key.substring(key.indexOf(KEY_SEPARATOR) + KEY_SEPARATOR.length());
  }

  /**
   * How one tier behaves.
   *
   * @param name the tier's name in reports, such as {@code edge-asia} or {@code shield}
   * @param fillTime how long a miss takes to come back from upstream; requests in that window find
   *     the entry still filling
   * @param coalescesMisses whether requests for a filling entry wait for it (NGINX {@code
   *     proxy_cache_lock on}) instead of going upstream themselves
   * @param speaksAge whether the tier reads and sends {@code Age} (RFC 9111 caches do; NGINX does
   *     not), which decides whether two tiers' lifetimes add up
   * @param neverStorePrefixes paths this tier passes through and never stores, such as {@code /v1/}
   */
  public record Settings(
      String name,
      Duration fillTime,
      boolean coalescesMisses,
      boolean speaksAge,
      List<String> neverStorePrefixes) {

    /** Validates every field and copies the prefix list. */
    public Settings {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(fillTime, "fillTime");
      if (fillTime.isNegative()) {
        throw new IllegalArgumentException("fillTime must not be negative");
      }
      neverStorePrefixes = List.copyOf(neverStorePrefixes);
    }

    /**
     * SeatWave's rules for every tier, the ones {@code nginx/templates/cdn-tier.conf.template}
     * mirrors: coalesce misses, speak Age, and never store anything under {@code /v1/}, the API,
     * whatever header it carries.
     */
    public static Settings seatWaveTier(String name, Duration fillTime) {
      return new Settings(name, fillTime, true, true, List.of(API_PREFIX));
    }
  }

  /** Counts for one tier; hit ratio counts only requests the tier was allowed to cache. */
  public record Stats(
      String tierName,
      long requests,
      long hits,
      long misses,
      long revalidated,
      long staleServed,
      long backgroundRefreshes,
      long coalesced,
      long bypassed,
      long upstreamRequests,
      long upstreamBytes,
      int storedEntries) {

    /** Answers without a full fetch upstream, divided by every cacheable request. */
    public double hitRatio() {
      long cacheable = requests - bypassed;
      return cacheable == 0
          ? 0
          : (double) (hits + staleServed + coalesced + revalidated) / cacheable;
    }
  }

  /** Whether a revalidation answers the request that caused it, or runs behind a stale copy. */
  private enum Refresh {
    ON_REQUEST,
    BACKGROUND
  }

  private record StoredEntry(
      Reply reply,
      Instant storedAt,
      Duration initialAge,
      Instant availableAt,
      Duration lifetime,
      Duration staleWindow) {

    Duration ageAt(Instant now) {
      // RFC 9111, Section 4.2.3: current age = the age it arrived with + the time it has been here.
      return initialAge.plus(Duration.between(storedAt, now));
    }
  }

  private static final class Counters {
    private long requests;
    private long hits;
    private long misses;
    private long revalidated;
    private long staleServed;
    private long backgroundRefreshes;
    private long coalesced;
    private long bypassed;
    private long upstreamRequests;
    private long upstreamBytes;
  }
}
