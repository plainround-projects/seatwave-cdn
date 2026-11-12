package io.plainround.seatwave.cdn.demo;

import io.plainround.seatwave.cdn.assets.AssetVersioner;
import io.plainround.seatwave.cdn.edge.EdgeCache;
import io.plainround.seatwave.cdn.http.Reply;
import io.plainround.seatwave.cdn.http.Upstream;
import io.plainround.seatwave.cdn.http.Upstream.Request;
import io.plainround.seatwave.cdn.origin.HeaderPolicy;
import io.plainround.seatwave.cdn.origin.OriginServer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release during load, five ways (Figure 2, Section 7, Lab 3). Fans on five continents load the
 * event page, its script bundle and its poster through 20 edges, a shield and the origin; release 2
 * ships 30 s in. For each way it counts broken pages (a page whose files are from another release,
 * or missing), how long the old page was still served, and what reached the origin. Deterministic:
 * a stepped clock and a fixed arrival order.
 */
public final class ReleaseDemoCli {

  private static final String HOST = "static.seatwave.example";
  private static final String PAGE_PATH = "/events/arena.html";
  private static final int FANS_PER_SECOND = 3_333; // 200,000 fans in the first minute (Module 1)
  private static final int SIMULATED_SECONDS = 150;
  private static final int RELEASE_AT_SECOND = 30;
  private static final int SCRIPT_BYTES = 1_000_000; // Section 7: 1 MB of scripts per first visit
  private static final int POSTER_BYTES = 300_000; // Module 9's poster
  private static final int EDGES_PER_CONTINENT = 4;
  private static final Duration PURGE_SPREAD =
      Duration.ofSeconds(5); // assumed: purge reaches the last edge 5 s after the first
  private static final Duration SHIELD_FILL_TIME =
      Duration.ofMillis(5); // shield and origin in one region
  private static final String TODAY_PAGE_HEADER = "public, max-age=0, s-maxage=60";
  private static final String TODAY_ASSET_HEADER = "public, max-age=300"; // Module 9's static files
  private static final int ONE_HUNDRED = 100;
  private static final int MILLIS_PER_SECOND = 1_000;
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final double BYTES_PER_MEGABYTE = 1e6;
  private static final int FIRST_RELEASE = 1;
  private static final int SECOND_RELEASE = 2;
  private static final int BYTE_PATTERN_STEP = 31;
  private static final int RELEASE_HEAD_BYTES = 16;
  private static final int HTTP_OK = 200;
  private static final Pattern RELEASE_MARK = Pattern.compile("release-(\\d+)");
  private static final Pattern ASSET_LINK = Pattern.compile("\"(/static/[^\"]+)\"");

  /** A continent's share of fans, in percent, and the time an edge there takes to fill a miss. */
  private record Continent(String name, int sharePercent, Duration fillTime) {}

  /** Assumed: the origin region is in North America; times are one round trip (Module 5). */
  private static final List<Continent> CONTINENTS =
      List.of(
          new Continent("North America", 60, Duration.ofMillis(20)),
          new Continent("Europe", 12, Duration.ofMillis(80)),
          new Continent("Asia", 15, Duration.ofMillis(200)),
          new Continent("South America", 8, Duration.ofMillis(120)),
          new Continent("Oceania", 5, Duration.ofMillis(200)));

  /** One way of shipping a release. */
  private record Way(
      String label,
      boolean isVersioned,
      boolean purgesAtRelease,
      boolean hasShield,
      boolean coalesces,
      boolean speaksAge) {}

  private static final List<Way> WAYS =
      List.of(
          new Way("A same names, lifetimes only (today)", false, false, true, true, true),
          new Way("B same names, purge all at release", false, true, true, true, true),
          new Way("C as B, no shield, no lock", false, true, false, false, true),
          new Way("D versioned names, immutable (chosen)", true, false, true, true, true),
          new Way("E as D, tiers ignore Age (NGINX)", true, false, true, true, false));

  private Instant simulatedNow;

  private ReleaseDemoCli() {}

  /** Runs the five ways and prints one row each. No arguments. */
  public static void main(String[] arguments) {
    System.out.printf(
        Locale.ROOT,
        "%d fans/s for %d s, release 2 at %d s; %d edges on %d continents; page, %,d-byte script,"
            + " %,d-byte poster%n",
        FANS_PER_SECOND,
        SIMULATED_SECONDS,
        RELEASE_AT_SECOND,
        EDGES_PER_CONTINENT * CONTINENTS.size(),
        CONTINENTS.size(),
        SCRIPT_BYTES,
        POSTER_BYTES);
    System.out.println(
        "way                                      pages    broken  old page until  origin req "
            + " origin req     origin MB   edge hit");
    System.out.println(
        "                                                          (s after rel.)  (whole run) "
            + " peak 1 s after  after rel.  ratio");
    for (Way way : WAYS) {
      new ReleaseDemoCli().runOneWay(way);
    }
  }

  private void runOneWay(Way way) {
    simulatedNow = Instant.parse("2026-11-11T09:30:00Z");
    Instant start = simulatedNow;
    Instant releaseAt = start.plusSeconds(RELEASE_AT_SECOND);
    Release first = Release.build(FIRST_RELEASE, way.isVersioned());
    Release second = Release.build(SECOND_RELEASE, way.isVersioned());
    UnaryOperator<String> headerRule =
        way.isVersioned()
            ? HeaderPolicy::cacheControlFor
            : path -> path.equals(PAGE_PATH) ? TODAY_PAGE_HEADER : TODAY_ASSET_HEADER;
    OriginServer origin = new OriginServer(first.files(), headerRule);
    Upstream edgeUpstream = origin;
    EdgeCache shield = null;
    if (way.hasShield()) {
      shield = new EdgeCache(settings("shield", SHIELD_FILL_TIME, way), origin, () -> simulatedNow);
      edgeUpstream = shield;
    }
    List<EdgeCache> edges = new ArrayList<>();
    for (Continent continent : CONTINENTS) {
      for (int popIndex = 0; popIndex < EDGES_PER_CONTINENT; popIndex++) {
        edges.add(
            new EdgeCache(
                settings(continent.name() + "-" + popIndex, continent.fillTime(), way),
                edgeUpstream,
                () -> simulatedNow));
      }
    }
    long fans = (long) FANS_PER_SECOND * SIMULATED_SECONDS;
    long brokenPages = 0;
    double lastOldPageSeconds = 0;
    boolean isReleased = false;
    int nextEdgeToPurge = 0;
    long originBytesAtRelease = 0;
    TreeMap<Long, Long> originRequestsBySecond = new TreeMap<>();
    for (long fanIndex = 0; fanIndex < fans; fanIndex++) {
      simulatedNow = start.plusNanos(fanIndex * NANOS_PER_SECOND / FANS_PER_SECOND);
      if (!isReleased && !simulatedNow.isBefore(releaseAt)) {
        isReleased = true;
        originBytesAtRelease = origin.bodyBytesSent();
        // Versioned: the old release's files stay deployed beside the new (Section 8, rollout).
        origin.deploy(way.isVersioned() ? merged(first.files(), second.files()) : second.files());
        if (way.purgesAtRelease() && shield != null) {
          shield.purge("/");
        }
      }
      while (isReleased
          && way.purgesAtRelease()
          && nextEdgeToPurge < edges.size()
          && !simulatedNow.isBefore(
              releaseAt.plus(PURGE_SPREAD.multipliedBy(nextEdgeToPurge).dividedBy(edges.size())))) {
        edges.get(nextEdgeToPurge++).purge("/");
      }
      long originBefore = origin.requestCount();
      EdgeCache edge = edges.get(edgeFor(fanIndex));
      Reply page = edge.fetch(Request.get(HOST, PAGE_PATH));
      int pageRelease = releaseOf(page.bodyText());
      if (isReleased && pageRelease == FIRST_RELEASE) {
        lastOldPageSeconds =
            Duration.between(releaseAt, simulatedNow).toMillis() / (double) MILLIS_PER_SECOND;
      }
      Matcher links = ASSET_LINK.matcher(page.bodyText());
      boolean isBroken = false;
      while (links.find()) {
        Reply asset = edge.fetch(Request.get(HOST, links.group(1)));
        isBroken |= asset.status() != HTTP_OK || releaseOf(firstBytes(asset)) != pageRelease;
      }
      brokenPages += isBroken ? 1 : 0;
      long secondIndex = Duration.between(start, simulatedNow).toSeconds();
      originRequestsBySecond.merge(secondIndex, origin.requestCount() - originBefore, Long::sum);
    }
    long peakAfterRelease =
        originRequestsBySecond.tailMap((long) RELEASE_AT_SECOND).values().stream()
            .mapToLong(Long::longValue)
            .max()
            .orElse(0);
    long edgeHits =
        edges.stream()
            .mapToLong(edge ->
                edge.stats().hits()
                    + edge.stats().staleServed()
                    + edge.stats().coalesced()
                    + edge.stats().revalidated())
            .sum();
    long edgeRequests = edges.stream().mapToLong(edge -> edge.stats().requests()).sum();
    System.out.printf(
        Locale.ROOT,
        "%-38s %,8d  %,8d  %14.1f  %,11d  %,14d  %10.1f  %7.3f%%%n",
        way.label(),
        fans,
        brokenPages,
        lastOldPageSeconds,
        origin.requestCount(),
        peakAfterRelease,
        (origin.bodyBytesSent() - originBytesAtRelease) / BYTES_PER_MEGABYTE,
        (double) ONE_HUNDRED * edgeHits / edgeRequests);
  }

  private static EdgeCache.Settings settings(String name, Duration fillTime, Way way) {
    return new EdgeCache.Settings(
        name, fillTime, way.coalesces(), way.speaksAge(), List.of("/v1/"));
  }

  private static int edgeFor(long fanIndex) {
    // Fan n's continent follows the shares exactly in every block of 100 fans; its edge rotates.
    int percentile = (int) (fanIndex % ONE_HUNDRED);
    int continentIndex = 0;
    int shareSoFar = 0;
    for (Continent continent : CONTINENTS) {
      shareSoFar += continent.sharePercent();
      if (percentile < shareSoFar) {
        break;
      }
      continentIndex++;
    }
    return continentIndex * EDGES_PER_CONTINENT
        + (int) ((fanIndex / ONE_HUNDRED) % EDGES_PER_CONTINENT);
  }

  private static int releaseOf(String text) {
    Matcher mark = RELEASE_MARK.matcher(text);
    return mark.find() ? Integer.parseInt(mark.group(1)) : 0;
  }

  private static String firstBytes(Reply asset) {
    ByteBuffer body = asset.body();
    byte[] head = new byte[Math.min(body.remaining(), RELEASE_HEAD_BYTES)];
    body.get(head);
    return new String(head, StandardCharsets.US_ASCII);
  }

  private static Map<String, byte[]> merged(Map<String, byte[]> older, Map<String, byte[]> newer) {
    TreeMap<String, byte[]> both = new TreeMap<>(older);
    both.putAll(newer);
    return both;
  }

  /** The files of one release: a page naming a script and a poster, each marked with its number. */
  private record Release(Map<String, byte[]> files) {

    static Release build(int releaseNumber, boolean isVersioned) {
      byte[] script = markedBytes(releaseNumber, SCRIPT_BYTES);
      byte[] poster = markedBytes(releaseNumber, POSTER_BYTES);
      String scriptPath =
          isVersioned
              ? "/static/seat-map." + AssetVersioner.contentHash(script) + ".js"
              : "/static/seat-map.js";
      String posterPath =
          isVersioned
              ? "/static/poster-arena." + AssetVersioner.contentHash(poster) + ".jpg"
              : "/static/poster-arena.jpg";
      String page =
          "<!-- release-"
              + releaseNumber
              + " --><script src=\""
              + scriptPath
              + "\"></script><img src=\""
              + posterPath
              + "\">";
      return new Release(
          Map.of(
              PAGE_PATH,
              page.getBytes(StandardCharsets.UTF_8),
              scriptPath,
              script,
              posterPath,
              poster));
    }

    private static byte[] markedBytes(int releaseNumber, int length) {
      byte[] bytes = new byte[length];
      for (int byteIndex = 0; byteIndex < length; byteIndex++) {
        bytes[byteIndex] = (byte) (byteIndex * BYTE_PATTERN_STEP + releaseNumber);
      }
      byte[] mark = ("release-" + releaseNumber + "\n").getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(mark, 0, bytes, 0, mark.length);
      return bytes;
    }
  }
}
