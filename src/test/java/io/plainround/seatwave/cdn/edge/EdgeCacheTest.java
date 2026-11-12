package io.plainround.seatwave.cdn.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.cdn.http.Reply;
import io.plainround.seatwave.cdn.http.Upstream;
import io.plainround.seatwave.cdn.http.Upstream.Request;
import io.plainround.seatwave.cdn.origin.OriginServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One tier's rules on a stepped clock: what it stores, for how long, and what reaches upstream. */
class EdgeCacheTest {

  private static final String HOST = "static.seatwave.example";
  private static final String PAGE = "/events/arena.html";
  private static final String PAGE_HEADER =
      "public, max-age=0, s-maxage=60, stale-while-revalidate=30";

  private Instant now = Instant.parse("2026-11-12T09:00:00Z");

  @Test
  void aSecondRequestInsideTheLifetimeIsAHit() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache edge = tier(origin, Duration.ZERO, true, true);

    edge.fetch(page());
    now = now.plusSeconds(59);
    Reply second = edge.fetch(page());

    assertThat(second.header("x-cache-status")).contains("HIT");
    assertThat(second.header("age")).contains("59");
    assertThat(origin.requestCount()).isEqualTo(1);
  }

  @Test
  void insideTheStaleWindowTheOldCopyIsServedAndOneRefreshRuns() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache edge = tier(origin, Duration.ZERO, true, true);
    edge.fetch(page());

    now = now.plusSeconds(75);
    Reply stale = edge.fetch(page());

    assertThat(stale.header("x-cache-status")).contains("STALE");
    assertThat(edge.stats().backgroundRefreshes()).isEqualTo(1);
    assertThat(origin.requestCount()).isEqualTo(2);
  }

  @Test
  void pastTheStaleWindowAnUnchangedFileIsRevalidatedWith304() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache edge = tier(origin, Duration.ZERO, true, true);
    edge.fetch(page());
    long bytesAfterFirst = origin.bodyBytesSent();

    now = now.plusSeconds(200);
    Reply revalidated = edge.fetch(page());

    assertThat(revalidated.header("x-cache-status")).contains("REVALIDATED");
    assertThat(revalidated.bodyText()).isEqualTo("page");
    assertThat(origin.bodyBytesSent()).isEqualTo(bytesAfterFirst);
  }

  @Test
  void aChangedFileReplacesTheExpiredCopy() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache edge = tier(origin, Duration.ZERO, true, true);
    edge.fetch(page());
    origin.deploy(Map.of(PAGE, utf8("page 2")));

    now = now.plusSeconds(200);
    Reply changed = edge.fetch(page());

    assertThat(changed.header("x-cache-status")).contains("EXPIRED");
    assertThat(changed.bodyText()).isEqualTo("page 2");
  }

  @Test
  void withCoalescingRequestsDuringAFillWaitInsteadOfGoingUpstream() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache edge = tier(origin, Duration.ofMillis(200), true, true);

    edge.fetch(page());
    now = now.plusMillis(100);
    Reply waiter = edge.fetch(page());

    assertThat(waiter.header("x-cache-status")).contains("COALESCED");
    assertThat(origin.requestCount()).isEqualTo(1);
  }

  @Test
  void withoutCoalescingEveryRequestDuringAFillGoesUpstream() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache edge = tier(origin, Duration.ofMillis(200), false, true);

    edge.fetch(page());
    now = now.plusMillis(100);
    Reply herd = edge.fetch(page());

    assertThat(herd.header("x-cache-status")).contains("MISS");
    assertThat(origin.requestCount()).isEqualTo(2);
  }

  @Test
  void anEdgeThatSpeaksAgeKeepsAShieldsCopyOnlyForWhatIsLeftOfItsLifetime() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache shield = tier(origin, Duration.ZERO, true, true);
    EdgeCache edge = tier(shield, Duration.ZERO, true, true);
    shield.fetch(page());
    now = now.plusSeconds(50);

    edge.fetch(page());
    now = now.plusSeconds(20);
    Reply afterSeventySeconds = edge.fetch(page());

    assertThat(afterSeventySeconds.header("x-cache-status")).contains("STALE");
  }

  @Test
  void anEdgeThatIgnoresAgeKeepsAShieldsCopyAFullLifetimeMore() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache shield = tier(origin, Duration.ZERO, true, false);
    EdgeCache edge = tier(shield, Duration.ZERO, true, false);
    shield.fetch(page());
    now = now.plusSeconds(50);

    edge.fetch(page());
    now = now.plusSeconds(20);
    Reply afterSeventySeconds = edge.fetch(page());

    assertThat(afterSeventySeconds.header("x-cache-status")).contains("HIT");
    assertThat(afterSeventySeconds.header("age")).isEmpty();
  }

  @Test
  void aShieldAnswersAnEdgesRevalidationWith304FromItsOwnCopy() {
    OriginServer origin = origin("public, max-age=600");
    EdgeCache shield = tier(origin, Duration.ZERO, true, true);
    String entityTag = shield.fetch(page()).header("etag").orElseThrow();

    Reply answer = shield.fetch(page().withHeader("If-None-Match", entityTag));

    assertThat(answer.status()).isEqualTo(304);
    assertThat(answer.bodyLength()).isZero();
    assertThat(origin.requestCount()).isEqualTo(1);
  }

  @Test
  void aPurgeRemovesOnlyThePathsUnderItsPrefix() {
    OriginServer origin =
        new OriginServer(
            Map.of(PAGE, utf8("page"), "/static/a.0123456789ab.js", utf8("a")),
            path -> "public, max-age=600");
    EdgeCache edge = tier(origin, Duration.ZERO, true, true);
    edge.fetch(page());
    edge.fetch(Request.get(HOST, "/static/a.0123456789ab.js"));

    int removed = edge.purge("/static/");

    assertThat(removed).isEqualTo(1);
    assertThat(edge.fetch(page()).header("x-cache-status")).contains("HIT");
  }

  @Test
  void aResponseThatSetsACookieOrVariesIsNeverStored() {
    Upstream cookieOrigin =
        request ->
            Reply.of(
                200,
                Map.of("cache-control", "public, max-age=600", "set-cookie", "fan=f-42"),
                utf8("hello"));
    Upstream varyingOrigin =
        request ->
            Reply.of(
                200,
                Map.of("cache-control", "public, max-age=600", "vary", "accept-language"),
                utf8("hello"));
    EdgeCache cookieEdge = tier(cookieOrigin, Duration.ZERO, true, true);
    EdgeCache varyingEdge = tier(varyingOrigin, Duration.ZERO, true, true);

    cookieEdge.fetch(page());
    varyingEdge.fetch(page());

    assertThat(cookieEdge.stats().storedEntries()).isZero();
    assertThat(varyingEdge.stats().storedEntries()).isZero();
  }

  @Test
  void aPrivateOrNoStoreResponseIsNeverStoredEvenWithALifetime() {
    OriginServer privateOrigin = origin("private, max-age=600");
    OriginServer noStoreOrigin = origin("no-store, s-maxage=600");
    EdgeCache privateEdge = tier(privateOrigin, Duration.ZERO, true, true);
    EdgeCache noStoreEdge = tier(noStoreOrigin, Duration.ZERO, true, true);

    privateEdge.fetch(page());
    noStoreEdge.fetch(page());

    assertThat(privateEdge.stats().storedEntries()).isZero();
    assertThat(noStoreEdge.stats().storedEntries()).isZero();
  }

  @Test
  void aResponseWithoutAnExplicitLifetimeIsNeverStored() {
    OriginServer origin = origin("public");
    EdgeCache edge = tier(origin, Duration.ZERO, true, true);

    edge.fetch(page());
    edge.fetch(page());

    assertThat(origin.requestCount()).isEqualTo(2);
  }

  @Test
  void theHitRatioCountsOnlyRequestsTheTierMayCache() {
    OriginServer origin = origin(PAGE_HEADER);
    EdgeCache edge = tier(origin, Duration.ZERO, true, true);
    edge.fetch(page());
    edge.fetch(page());
    edge.fetch(Request.get(HOST, "/v1/holds/h-1"));

    EdgeCache.Stats stats = edge.stats();

    assertThat(stats.bypassed()).isEqualTo(1);
    assertThat(stats.hitRatio()).isEqualTo(0.5);
  }

  @Test
  void aTierThatWasNeverAskedHasAHitRatioOfZero() {
    assertThat(tier(origin(PAGE_HEADER), Duration.ZERO, true, true).stats().hitRatio()).isZero();
  }

  private OriginServer origin(String header) {
    return new OriginServer(Map.of(PAGE, utf8("page")), path -> header);
  }

  private EdgeCache tier(
      Upstream upstream, Duration fillTime, boolean coalesces, boolean speaksAge) {
    return new EdgeCache(
        new EdgeCache.Settings("tier", fillTime, coalesces, speaksAge, List.of("/v1/")),
        upstream,
        () -> now);
  }

  private static Request page() {
    return Request.get(HOST, PAGE);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
