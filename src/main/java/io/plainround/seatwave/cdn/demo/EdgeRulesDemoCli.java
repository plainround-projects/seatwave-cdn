package io.plainround.seatwave.cdn.demo;

import io.plainround.seatwave.cdn.assets.AssetVersioner;
import io.plainround.seatwave.cdn.edge.EdgeCache;
import io.plainround.seatwave.cdn.http.Reply;
import io.plainround.seatwave.cdn.http.Upstream.Request;
import io.plainround.seatwave.cdn.origin.HeaderPolicy;
import io.plainround.seatwave.cdn.origin.OriginServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Walks one edge through the rules of Sections 3 and 5 on a stepped clock, so every line of its
 * output is the same on every run: a miss and a hit, Age, expiry and a 304, stale-while-revalidate,
 * what is never stored, a misconfigured hold with and without the edge's never-store rule, an
 * unreadable header, and a purge.
 */
public final class EdgeRulesDemoCli {

  private static final String HOST = "static.seatwave.example";
  private static final int POSTER_BYTES = 300_000; // Module 9's poster
  private static final Duration EDGE_FILL_TIME = Duration.ZERO; // one request at a time here
  private static final String MISCONFIGURED_HOLD_HEADER = "public, max-age=60";
  private static final String UNREADABLE_SHARE_HEADER = "public, max-age=3600s";
  // The trace's moments, in seconds from the start: chosen around the page's 60 s + 30 s windows.
  private static final long PAGE_STILL_FRESH = 59; // just inside s-maxage=60
  private static final long PAGE_IN_STALE_WINDOW = 75; // past 60, inside 60 + 30
  private static final long PAGE_LONG_EXPIRED = 200; // past both windows
  private static final int BYTE_PATTERN_STEP = 31; // any odd step gives a fixed, varied pattern

  private Instant simulatedNow = Instant.parse("2026-11-11T09:00:00Z");
  private final Instant start = simulatedNow;

  private EdgeRulesDemoCli() {}

  /** Prints the trace. No arguments. */
  public static void main(String[] arguments) {
    new EdgeRulesDemoCli().run();
  }

  private void run() {
    byte[] poster = standInBytes(POSTER_BYTES);
    String posterPath = "/static/poster-arena." + AssetVersioner.contentHash(poster) + ".jpg";
    Map<String, byte[]> files =
        Map.of(
            posterPath,
            poster,
            "/events/arena.html",
            utf8("<img src=\"" + posterPath + "\">"),
            "/share/poster-arena.jpg",
            poster,
            // A fixed body standing in for Module 7's hold response, so the demo needs no API.
            "/v1/holds/h-1",
            utf8("{\"holdId\":\"h-1\",\"fanId\":\"f-42\",\"seat\":\"A-1\"}"));
    UnaryOperator<String> headerRule =
        path ->
            switch (path) {
              // Two mistakes a release can make, replayed on purpose (failure mode 1).
              case "/v1/holds/h-1" -> MISCONFIGURED_HOLD_HEADER;
              case "/share/poster-arena.jpg" -> UNREADABLE_SHARE_HEADER;
              default -> HeaderPolicy.cacheControlFor(path);
            };
    OriginServer origin = new OriginServer(files, headerRule);
    EdgeCache edge =
        new EdgeCache(
            EdgeCache.Settings.seatWaveTier("edge", EDGE_FILL_TIME), origin, () -> simulatedNow);
    EdgeCache edgeWithoutRule =
        new EdgeCache(
            new EdgeCache.Settings("edge-no-rule", EDGE_FILL_TIME, true, true, List.of()),
            origin,
            () -> simulatedNow);

    System.out.println("headers the origin sends:");
    for (String path :
        List.of(posterPath, "/events/arena.html", "/share/poster-arena.jpg", "/v1/holds/h-1")) {
      System.out.println("  " + path + "  Cache-Control: " + headerRule.apply(path));
    }
    System.out.println();
    System.out.println(
        "   t (s)  tier          request                                        status  x-cache    "
            + "  age  origin requests");
    request(0, edge, origin, posterPath, Map.of());
    request(1, edge, origin, posterPath, Map.of());
    request(0, edge, origin, "/events/arena.html", Map.of());
    request(PAGE_STILL_FRESH, edge, origin, "/events/arena.html", Map.of());
    request(PAGE_IN_STALE_WINDOW, edge, origin, "/events/arena.html", Map.of());
    request(PAGE_IN_STALE_WINDOW + 1, edge, origin, "/events/arena.html", Map.of());
    request(PAGE_LONG_EXPIRED, edge, origin, "/events/arena.html", Map.of());
    request(
        PAGE_LONG_EXPIRED,
        edge,
        origin,
        "/events/arena.html",
        Map.of("authorization", "Bearer fan-token"));
    request(PAGE_LONG_EXPIRED + 1, edge, origin, "/v1/holds/h-1", Map.of());
    request(PAGE_LONG_EXPIRED + 2, edge, origin, "/v1/holds/h-1", Map.of());
    request(PAGE_LONG_EXPIRED + 1, edgeWithoutRule, origin, "/v1/holds/h-1", Map.of());
    request(PAGE_LONG_EXPIRED + 2, edgeWithoutRule, origin, "/v1/holds/h-1", Map.of());
    System.out.println(
        "          the second fan on edge-no-rule was sent: "
            + edgeWithoutRule.fetch(Request.get(HOST, "/v1/holds/h-1")).bodyText());
    request(PAGE_LONG_EXPIRED + 3, edge, origin, "/share/poster-arena.jpg", Map.of());
    request(PAGE_LONG_EXPIRED + 4, edge, origin, "/share/poster-arena.jpg", Map.of());
    System.out.println();
    System.out.println("purge \"/static/\" removed " + edge.purge("/static/") + " entry");
    request(PAGE_LONG_EXPIRED + 5, edge, origin, posterPath, Map.of());
    System.out.println();
    System.out.println(edge.stats());
    System.out.printf(
        Locale.ROOT,
        "edge hit ratio %.1f%%, origin body bytes %,d%n",
        100 * edge.stats().hitRatio(),
        origin.bodyBytesSent());
  }

  private void request(
      long secondsFromStart,
      EdgeCache tier,
      OriginServer origin,
      String path,
      Map<String, String> headers) {
    simulatedNow = start.plusSeconds(secondsFromStart);
    Reply reply = tier.fetch(new Request("GET", HOST, path, headers));
    String shownPath = headers.isEmpty() ? path : path + " (with Authorization)";
    System.out.printf(
        Locale.ROOT,
        "  %6d  %-12s  %-45s  %6d  %-11s  %4s  %15d%n",
        secondsFromStart,
        tier.stats().tierName(),
        shownPath,
        reply.status(),
        reply.header("x-cache-status").orElse(""),
        reply.header("age").orElse("-"),
        origin.requestCount());
  }

  private static byte[] standInBytes(int length) {
    // A stand-in for an image: fixed bytes, so its hash, and so its versioned name, never change.
    byte[] bytes = new byte[length];
    for (int byteIndex = 0; byteIndex < length; byteIndex++) {
      bytes[byteIndex] = (byte) (byteIndex * BYTE_PATTERN_STEP);
    }
    return bytes;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
