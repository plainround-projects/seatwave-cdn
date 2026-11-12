package io.plainround.seatwave.cdn.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.cdn.http.CacheControl;
import io.plainround.seatwave.cdn.http.Reply;
import io.plainround.seatwave.cdn.http.Upstream.Request;
import io.plainround.seatwave.cdn.origin.HeaderPolicy;
import io.plainround.seatwave.cdn.origin.OriginServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The module's invariant: no response containing personal data is ever cacheable at the edge. It is
 * held in two places that must both fail for a leak: the origin's {@link HeaderPolicy} marks every
 * personal path {@code private, no-store} (and every unknown path too), and every SeatWave tier
 * refuses to store anything under {@code /v1/} or answer a request with credentials from cache,
 * whatever header the origin sent.
 */
class PersonalResponsesNeverCachedTest {

  private static final String HOST = "static.seatwave.example";
  private static final InstantSource FIXED_CLOCK =
      InstantSource.fixed(Instant.parse("2026-11-11T09:00:00Z"));

  /** Every personal endpoint of Modules 7 and 11, with a sample path. */
  private static final List<String> PERSONAL_PATHS =
      List.of("/v1/holds", "/v1/holds/h-1", "/v1/bookings", "/v1/bookings/b-7", "/v1/tokens");

  @Test
  void everyPersonalEndpointIsSentPrivateOrNoStore() {
    for (String path : PERSONAL_PATHS) {
      CacheControl directives = CacheControl.parse(HeaderPolicy.cacheControlFor(path));

      assertThat(directives.forbidsSharedStorage()).as(path).isTrue();
    }
  }

  @Test
  void anUnclassifiedPathIsTreatedAsPersonal() {
    for (String path :
        List.of("/v1/new-endpoint", "/static/app.js", "/admin", "/events/Arena.HTML")) {
      assertThat(HeaderPolicy.cacheControlFor(path)).as(path).isEqualTo("private, no-store");
    }
  }

  @Test
  void edgeNeverStoresAHoldEvenWhenTheOriginWronglySaysPublic() {
    OriginServer misconfiguredOrigin =
        new OriginServer(
            Map.of("/v1/holds/h-1", utf8("{\"fanId\":\"f-42\"}")), path -> "public, max-age=60");
    EdgeCache edge =
        new EdgeCache(
            EdgeCache.Settings.seatWaveTier("edge", Duration.ZERO),
            misconfiguredOrigin,
            FIXED_CLOCK);

    edge.fetch(Request.get(HOST, "/v1/holds/h-1"));
    Reply secondFan = edge.fetch(Request.get(HOST, "/v1/holds/h-1"));

    assertThat(secondFan.header("x-cache-status")).contains("BYPASS");
    assertThat(misconfiguredOrigin.requestCount()).isEqualTo(2);
    assertThat(edge.stats().storedEntries()).isZero();
  }

  @Test
  void edgeNeverAnswersARequestWithCredentialsFromItsCache() {
    OriginServer origin = new OriginServer(Map.of("/events/arena.html", utf8("<html>page</html>")));
    EdgeCache edge =
        new EdgeCache(EdgeCache.Settings.seatWaveTier("edge", Duration.ZERO), origin, FIXED_CLOCK);
    edge.fetch(Request.get(HOST, "/events/arena.html"));

    Reply withCredentials =
        edge.fetch(
            Request.get(HOST, "/events/arena.html")
                .withHeader("Authorization", "Bearer fan-token"));

    assertThat(withCredentials.header("x-cache-status")).contains("BYPASS");
    assertThat(origin.requestCount()).isEqualTo(2);
  }

  @Test
  void anUnreadableHeaderIsNeverStored() {
    OriginServer origin =
        new OriginServer(
            Map.of("/share/poster-arena.jpg", utf8("poster")), path -> "public, max-age=3600s");
    EdgeCache edge =
        new EdgeCache(EdgeCache.Settings.seatWaveTier("edge", Duration.ZERO), origin, FIXED_CLOCK);

    edge.fetch(Request.get(HOST, "/share/poster-arena.jpg"));
    edge.fetch(Request.get(HOST, "/share/poster-arena.jpg"));

    assertThat(origin.requestCount()).isEqualTo(2);
    assertThat(edge.stats().storedEntries()).isZero();
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
