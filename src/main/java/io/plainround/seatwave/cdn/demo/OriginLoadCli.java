package io.plainround.seatwave.cdn.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * The estimates of Sections 6e and 7 and Lab 3, computed instead of typed: origin load from hit
 * ratio, with and without a shield; first-visit time by region from Module 5's round-trip counting;
 * and the monthly egress bill at list prices checked on 11 November 2026. Every input is a named
 * constant with its source; nothing here is measured.
 */
public final class OriginLoadCli {

  private static final long FANS_FIRST_MINUTE = 200_000; // Module 1's on-sale crowd
  private static final long REQUESTS_PER_FIRST_VISIT = 3; // page, script bundle, poster
  private static final long SECONDS_PER_MINUTE = 60;
  private static final long PAGE_BYTES = 20_000; // assumed size of the event page's HTML
  private static final long SCRIPT_BYTES = 1_000_000; // Section 7: 1 MB of scripts
  private static final long POSTER_BYTES = 300_000; // Module 9's poster
  private static final double NIC_LIMIT_GBPS = 12.5; // Module 1's "up to 12.5 Gbps"
  private static final double BITS_PER_BYTE = 8;
  private static final double BYTES_PER_GIGABYTE = 1e9;
  private static final List<Double> EDGE_HIT_RATIOS = List.of(0.0, 0.90, 0.95, 0.99, 0.999);
  private static final double SHIELD_HIT_RATIO_OF_EDGE_MISSES = 0.90; // assumed

  /** Module 5: a 10-segment initial window of 1,460 bytes, doubling each round trip. */
  private static final long INITIAL_WINDOW_BYTES = 14_600;

  private static final int SETUP_ROUND_TRIPS = 2; // TCP + TLS 1.3 on a new connection (Module 5)
  private static final int DISCOVERY_ROUND_TRIPS =
      1; // the page must arrive before its assets are asked for

  /** Assumed round trips in ms: fan to the origin's region, fan to a nearby edge. */
  private record Region(
      String name,
      int sharePercent,
      int originRoundTripMillis,
      int edgeRoundTripMillis,
      String cloudFrontUsdPerGigabyte) {}

  // Prices: AWS Price List API, CloudFront first 10 TB a month by viewer region, 11 November 2026.
  private static final List<Region> REGIONS =
      List.of(
          new Region("North America", 60, 30, 10, "0.085"),
          new Region("Europe", 12, 90, 15, "0.085"),
          new Region("Asia", 15, 200, 20, "0.120"),
          new Region("South America", 8, 130, 20, "0.110"),
          new Region("Oceania", 5, 210, 20, "0.114"));

  private static final BigDecimal ORIGIN_USD_PER_GIGABYTE =
      new BigDecimal("0.090"); // EC2 to internet, us-east-1, first 10 TB
  private static final BigDecimal HTTPS_USD_PER_10K_REQUESTS =
      new BigDecimal("0.0100"); // CloudFront, US; Asia is 0.012
  private static final long POSTER_GIGABYTES_PER_MONTH =
      45_000; // Module 3: 5,000,000 fans a day x 300 KB x 30 days
  private static final long POSTER_REQUESTS_PER_MONTH =
      150_000_000; // Module 3: 1 poster per fan per day x 30 days
  private static final int PERCENT = 100;
  private static final int PRICE_DECIMALS = 4; // a blended price to a hundredth of a cent
  private static final int CENT_DECIMALS = 2;
  private static final BigDecimal TEN_THOUSAND = new BigDecimal(10_000);

  private OriginLoadCli() {}

  /** Prints the three estimates. No arguments. */
  public static void main(String[] arguments) {
    printOriginLoad();
    printFirstVisitTimes();
    printMonthlyEgress();
  }

  private static void printOriginLoad() {
    long requestsPerSecond = FANS_FIRST_MINUTE * REQUESTS_PER_FIRST_VISIT / SECONDS_PER_MINUTE;
    long bytesPerFan = PAGE_BYTES + SCRIPT_BYTES + POSTER_BYTES;
    double bytesPerRequest = (double) bytesPerFan / REQUESTS_PER_FIRST_VISIT;
    System.out.printf(
        Locale.ROOT,
        "1. Origin load at %,d static requests/s (%,d fans in a minute, %,d bytes each)%n",
        requestsPerSecond,
        FANS_FIRST_MINUTE,
        bytesPerFan);
    System.out.println(
        "   edge hit ratio   origin req/s   origin Gbps   with shield (90% of misses): req/s  "
            + " Gbps");
    for (double hitRatio : EDGE_HIT_RATIOS) {
      // origin load = requests x (1 - hit ratio); a shield removes a share of what is left.
      double originRequests = requestsPerSecond * (1 - hitRatio);
      double shieldedRequests =
          hitRatio == 0 ? originRequests : originRequests * (1 - SHIELD_HIT_RATIO_OF_EDGE_MISSES);
      System.out.printf(
          Locale.ROOT,
          "   %13.1f%%   %12.1f   %11.2f   %35.1f   %5.2f%n",
          PERCENT * hitRatio,
          originRequests,
          gbps(originRequests * bytesPerRequest),
          shieldedRequests,
          gbps(shieldedRequests * bytesPerRequest));
    }
    System.out.printf(
        Locale.ROOT, "   one reference machine's network: up to %.1f Gbps%n%n", NIC_LIMIT_GBPS);
  }

  private static void printFirstVisitTimes() {
    long firstVisitBytes = PAGE_BYTES + SCRIPT_BYTES + POSTER_BYTES;
    int dataRounds = slowStartRounds(firstVisitBytes);
    int posterRounds = slowStartRounds(POSTER_BYTES);
    int visitRoundTrips = SETUP_ROUND_TRIPS + DISCOVERY_ROUND_TRIPS + dataRounds;
    int firstByteRoundTrips = SETUP_ROUND_TRIPS + 1;
    System.out.printf(
        Locale.ROOT,
        "2. First visit on a new connection: %d setup + %d discovery + %d data rounds = %d round"
            + " trips for %,d bytes (a poster alone: %d data rounds)%n",
        SETUP_ROUND_TRIPS,
        DISCOVERY_ROUND_TRIPS,
        dataRounds,
        visitRoundTrips,
        firstVisitBytes,
        posterRounds);
    System.out.println(
        "   region          share  RTT origin/edge   first byte origin/edge (ms)   first visit"
            + " origin/edge (ms)   edge miss adds (ms)");
    for (Region region : REGIONS) {
      System.out.printf(
          Locale.ROOT,
          "   %-14s %5d%%  %6d / %-6d   %12d / %-12d   %13d / %-13d   %8d%n",
          region.name(),
          region.sharePercent(),
          region.originRoundTripMillis(),
          region.edgeRoundTripMillis(),
          firstByteRoundTrips * region.originRoundTripMillis(),
          firstByteRoundTrips * region.edgeRoundTripMillis(),
          visitRoundTrips * region.originRoundTripMillis(),
          visitRoundTrips * region.edgeRoundTripMillis(),
          // A miss crosses the long way once, on the edge's warm connection to the shield.
          region.originRoundTripMillis());
    }
    System.out.println();
  }

  private static void printMonthlyEgress() {
    BigDecimal gigabytes = BigDecimal.valueOf(POSTER_GIGABYTES_PER_MONTH);
    BigDecimal fromOrigin = gigabytes.multiply(ORIGIN_USD_PER_GIGABYTE);
    BigDecimal blendedCdnPrice = BigDecimal.ZERO;
    for (Region region : REGIONS) {
      blendedCdnPrice =
          blendedCdnPrice.add(
              new BigDecimal(region.cloudFrontUsdPerGigabyte())
                  .multiply(BigDecimal.valueOf(region.sharePercent())));
    }
    blendedCdnPrice =
        blendedCdnPrice.divide(BigDecimal.valueOf(PERCENT), PRICE_DECIMALS, RoundingMode.HALF_UP);
    BigDecimal fromEdge = gigabytes.multiply(blendedCdnPrice);
    BigDecimal requestFees =
        BigDecimal.valueOf(POSTER_REQUESTS_PER_MONTH)
            .divide(TEN_THOUSAND)
            .multiply(HTTPS_USD_PER_10K_REQUESTS);
    System.out.printf(
        Locale.ROOT,
        "3. Posters, %,d GB a month (Module 3), first-tier list prices%n",
        POSTER_GIGABYTES_PER_MONTH);
    System.out.printf(
        Locale.ROOT,
        "   from the origin to the internet at $%s/GB:            $%s%n",
        ORIGIN_USD_PER_GIGABYTE.toPlainString(),
        fromOrigin.setScale(CENT_DECIMALS, RoundingMode.HALF_UP).toPlainString());
    System.out.printf(
        Locale.ROOT,
        "   from edges at the blended $%s/GB (shares above):  $%s%n",
        blendedCdnPrice.toPlainString(),
        fromEdge.setScale(CENT_DECIMALS, RoundingMode.HALF_UP).toPlainString());
    System.out.printf(
        Locale.ROOT,
        "   plus %,d HTTPS requests at $%s per 10,000:      $%s%n",
        POSTER_REQUESTS_PER_MONTH,
        HTTPS_USD_PER_10K_REQUESTS.toPlainString(),
        requestFees.setScale(CENT_DECIMALS, RoundingMode.HALF_UP).toPlainString());
    System.out.println(
        "   origin to edge inside AWS: $0.00/GB, so misses add no origin egress fee");
  }

  private static int slowStartRounds(long bytes) {
    // Round k carries INITIAL_WINDOW_BYTES x 2^(k-1); count rounds until the sum covers the bytes.
    int rounds = 0;
    long carried = 0;
    long window = INITIAL_WINDOW_BYTES;
    while (carried < bytes) {
      carried = Math.addExact(carried, window);
      window = Math.multiplyExact(window, 2);
      rounds++;
    }
    return rounds;
  }

  private static double gbps(double bytesPerSecond) {
    return bytesPerSecond * BITS_PER_BYTE / BYTES_PER_GIGABYTE;
  }
}
