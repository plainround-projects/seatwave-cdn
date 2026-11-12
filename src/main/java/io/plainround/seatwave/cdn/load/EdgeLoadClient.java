package io.plainround.seatwave.cdn.load;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays first visits against an edge (or straight against the origin, for the BEFORE run): each fan
 * loads the event page, then every {@code /static/} file it names. It counts {@code X-Cache-Status}
 * values, reads the origin's counters before and after, and prints hit ratio, origin requests and
 * bytes, throughput, and p50 and p99 latency.
 *
 * <p>Arguments: page URL, origin stats URL, fans, concurrent fans, and optionally the file holding
 * the origin's secret. Example: {@code http://127.0.0.1:21701/events/arena.html
 * http://127.0.0.1:21700/_origin/stats 20000 64 .secrets/origin-secret}. The secret is sent to the
 * stats URL, and to the fans' requests only when they go straight to the origin (the same host and
 * port as the stats URL); through the edge, the tiers add it themselves.
 *
 * <p>Thread-safe while it runs: the counters are concurrent, and each fan is one virtual thread.
 */
public final class EdgeLoadClient {

  private static final int HTTP_OK = 200;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final int EXIT_SETTINGS_PROBLEM = 2;
  private static final int EXIT_NO_RESULT = 1;
  private static final double P50 = 0.50;
  private static final double P99 = 0.99;
  private static final double NANOS_PER_MILLI = 1e6;
  private static final double NANOS_PER_SECOND = 1e9;
  private static final double BITS_PER_BYTE = 8;
  private static final double BITS_PER_GIGABIT = 1e9;
  private static final int PERCENT = 100;
  private static final Pattern STATIC_LINK = Pattern.compile("\"(/static/[^\"]+)\"");
  private static final Pattern ORIGIN_STATS = Pattern.compile("requests=(\\d+) bodyBytes=(\\d+)");
  private static final String SECRET_HEADER = "X-SeatWave-Origin-Secret";

  /** Statuses that mean the edge answered without a full fetch from upstream. */
  private static final List<String> ANSWERED_AT_EDGE =
      List.of("HIT", "STALE", "UPDATING", "REVALIDATED");

  /** The status counted for a response that carried no {@code X-Cache-Status} at all. */
  private static final String NO_CACHE_STATUS = "none";

  private final HttpClient client;
  private final Settings settings;
  private final Map<String, LongAdder> cacheStatuses = new ConcurrentHashMap<>();
  private final List<Long> latencyNanos = Collections.synchronizedList(new ArrayList<>());
  private final LongAdder failedRequests = new LongAdder();
  private final LongAdder bodyBytesReceived = new LongAdder();

  private EdgeLoadClient(HttpClient client, Settings settings) {
    this.client = client;
    this.settings = settings;
  }

  /** Runs one load and prints its report. Settings problems are printed together; exit status 2. */
  public static void main(String[] arguments) throws InterruptedException {
    Result<Settings, List<String>> parsed = Settings.parse(arguments);
    if (parsed.isError()) {
      parsed.peekError(
          problems -> problems.forEach(problem -> System.err.println("settings: " + problem)));
      System.exit(EXIT_SETTINGS_PROBLEM);
    }
    Settings settings = parsed.orOnErrorThrow(problems -> new IllegalStateException("checked"));
    // One client for the whole run: it pools connections, as a browser population does not, so
    // the latencies here are for reused connections (Module 5); first visits pay setup on top.
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()) {
      run(settings, client).lines().forEach(System.out::println);
    } catch (IOException noCounters) {
      // Without the origin's counters there is no result to print, only the reason.
      System.err.println("error: " + noCounters.getMessage());
      System.exit(EXIT_NO_RESULT);
    }
  }

  /**
   * Plays {@code settings.fans()} first visits with at most {@code settings.concurrentFans()} at a
   * time and returns what happened. Throws {@link IOException} if the origin's counters cannot be
   * read before or after, since a run without them would report no origin load at all.
   */
  public static LoadReport run(Settings settings, HttpClient client)
      throws IOException, InterruptedException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(client, "client");
    return new EdgeLoadClient(client, settings).play();
  }

  private LoadReport play() throws IOException, InterruptedException {
    long[] originBefore = readOriginStats();
    Semaphore fanSlots = new Semaphore(settings.concurrentFans());
    long startNanos = System.nanoTime();
    try (ExecutorService fanThreads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int fanIndex = 0; fanIndex < settings.fans(); fanIndex++) {
        fanSlots.acquire();
        fanThreads.submit(() -> {
          try {
            playOneFan();
          } finally {
            fanSlots.release();
          }
        });
      }
    }
    double elapsedSeconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND;
    long[] originAfter = readOriginStats();
    List<Long> sorted = new ArrayList<>(latencyNanos);
    Collections.sort(sorted);
    TreeMap<String, Long> statuses = new TreeMap<>();
    cacheStatuses.forEach((status, count) -> statuses.put(status, count.sum()));
    return new LoadReport(
        settings.fans(),
        sorted.size(),
        elapsedSeconds,
        failedRequests.sum(),
        statuses,
        originAfter[0] - originBefore[0],
        originAfter[1] - originBefore[1],
        bodyBytesReceived.sum(),
        percentile(sorted, P50) / NANOS_PER_MILLI,
        percentile(sorted, P99) / NANOS_PER_MILLI);
  }

  private void playOneFan() {
    timedGet(settings.pageUri())
        .ifPresent(page -> {
          Matcher links = STATIC_LINK.matcher(page);
          while (links.find()) {
            timedGet(settings.pageUri().resolve(links.group(1)));
          }
        });
  }

  private Optional<String> timedGet(URI uri) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET();
    if (settings.isStraightAtOrigin() && !settings.secret().isEmpty()) {
      request.header(SECRET_HEADER, settings.secret());
    }
    long startNanos = System.nanoTime();
    try {
      HttpResponse<byte[]> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      latencyNanos.add(System.nanoTime() - startNanos);
      String cacheStatus = response.headers().firstValue("x-cache-status").orElse(NO_CACHE_STATUS);
      cacheStatuses.computeIfAbsent(cacheStatus, status -> new LongAdder()).increment();
      bodyBytesReceived.add(response.body().length);
      // The whole answer: the status, and the promised length against the bytes that arrived.
      boolean isWhole =
          response.headers().firstValueAsLong("content-length").stream()
              .allMatch(promised -> promised == response.body().length);
      if (response.statusCode() != HTTP_OK || !isWhole) {
        failedRequests.increment();
        return Optional.empty();
      }
      return Optional.of(new String(response.body(), StandardCharsets.UTF_8));
    } catch (IOException failure) {
      // Counted, not hidden: a failed request is part of the result the report prints.
      failedRequests.increment();
      return Optional.empty();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failedRequests.increment();
      return Optional.empty();
    }
  }

  private long[] readOriginStats() throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(settings.statsUri()).timeout(REQUEST_TIMEOUT).GET();
    if (!settings.secret().isEmpty()) {
      request.header(SECRET_HEADER, settings.secret());
    }
    HttpResponse<String> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Matcher numbers = ORIGIN_STATS.matcher(response.body());
    if (response.statusCode() != HTTP_OK || !numbers.find()) {
      throw new IOException(
          "origin stats at "
              + settings.statsUri()
              + " answered "
              + response.statusCode()
              + ": "
              + response.body().strip());
    }
    return new long[] {Long.parseLong(numbers.group(1)), Long.parseLong(numbers.group(2))};
  }

  private static double percentile(List<Long> sorted, double fraction) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int index = (int) Math.ceil(fraction * sorted.size()) - 1;
    return sorted.get(Math.max(0, index));
  }

  /**
   * What one run measured.
   *
   * @param fans fans played
   * @param requests responses received (every request that got an answer, of any status)
   * @param elapsedSeconds wall time of the run
   * @param failedRequests requests without a whole {@code 200}: errors, other statuses, short
   *     bodies
   * @param cacheStatuses {@code X-Cache-Status} values and their counts over {@code requests}
   * @param originRequests the origin's request counter after minus before
   * @param originBodyBytes the origin's body-byte counter after minus before
   * @param bodyBytesReceived body bytes this client received
   * @param p50Millis median latency of the received responses
   * @param p99Millis 99th percentile latency of the received responses
   */
  public record LoadReport(
      int fans,
      long requests,
      double elapsedSeconds,
      long failedRequests,
      Map<String, Long> cacheStatuses,
      long originRequests,
      long originBodyBytes,
      long bodyBytesReceived,
      double p50Millis,
      double p99Millis) {

    /** Copies the status counts. */
    public LoadReport {
      cacheStatuses = Map.copyOf(cacheStatuses);
    }

    /**
     * The edge hit ratio as text: answers at the edge divided by the same received responses the
     * statuses count. Distinct texts for "no cache in front" and "nothing was received".
     */
    public String hitRatioText() {
      if (requests == 0) {
        return "not measured: no response was received";
      }
      if (cacheStatuses.keySet().equals(Set.of(NO_CACHE_STATUS))) {
        return "none (no cache in front)";
      }
      long answeredAtEdge =
          ANSWERED_AT_EDGE.stream()
              .mapToLong(status -> cacheStatuses.getOrDefault(status, 0L))
              .sum();
      // Three decimals: 3 misses in 60,000 is 99.995 percent, which two decimals would round to
      // 100.
      return String.format(Locale.ROOT, "%.3f%%", (double) PERCENT * answeredAtEdge / requests);
    }

    /** The report as the program prints it. */
    public List<String> lines() {
      double seconds = Math.max(elapsedSeconds, Double.MIN_VALUE);
      return List.of(
          String.format(
              Locale.ROOT,
              "fans %,d, requests %,d in %.2f s, failed %,d",
              fans,
              requests,
              elapsedSeconds,
              failedRequests),
          "x-cache-status " + new TreeMap<>(cacheStatuses),
          "edge hit ratio " + hitRatioText(),
          String.format(
              Locale.ROOT,
              "origin requests %,d (%.4f per fan), origin body bytes %,d",
              originRequests,
              (double) originRequests / fans,
              originBodyBytes),
          String.format(
              Locale.ROOT,
              "throughput %,.0f responses/s, %.2f Gbps of bodies received",
              requests / seconds,
              bodyBytesReceived * BITS_PER_BYTE / BITS_PER_GIGABIT / seconds),
          String.format(Locale.ROOT, "latency p50 %.3f ms, p99 %.3f ms", p50Millis, p99Millis));
    }
  }

  /**
   * One run's settings.
   *
   * @param pageUri the event page fans load first
   * @param statsUri the origin's counters, {@code /_origin/stats}
   * @param fans how many first visits to play
   * @param concurrentFans how many fans at once
   * @param secret the origin's secret, or empty when the origin asks for none
   */
  public record Settings(URI pageUri, URI statsUri, int fans, int concurrentFans, String secret) {

    private static final int MOST_FANS = 1_000_000;
    private static final int MOST_CONCURRENT_FANS = 1_024;
    // Nine digits always fit an int, so leading zeros never overflow the parse.
    private static final int MOST_NUMBER_DIGITS = 9;

    /** Checks the fields every caller must get right. */
    public Settings {
      Objects.requireNonNull(pageUri, "pageUri");
      Objects.requireNonNull(statsUri, "statsUri");
      Objects.requireNonNull(secret, "secret");
      if (fans < 1
          || fans > MOST_FANS
          || concurrentFans < 1
          || concurrentFans > MOST_CONCURRENT_FANS) {
        throw new IllegalArgumentException("fans or concurrent fans out of range");
      }
    }

    /** True when the fans' requests go to the origin itself, the BEFORE run. */
    boolean isStraightAtOrigin() {
      return Objects.equals(pageUri.getAuthority(), statsUri.getAuthority());
    }

    /**
     * Reads {@code <page-url> <origin-stats-url> <fans> <concurrent-fans> [<secret-file>]}; returns
     * every problem found, not only the first.
     */
    public static Result<Settings, List<String>> parse(String[] arguments) {
      Objects.requireNonNull(arguments, "arguments");
      if (arguments.length != 4 && arguments.length != 5) {
        return Result.error(
            List.of(
                "usage: EdgeLoadClient <page-url> <origin-stats-url> <fans> <concurrent-fans>"
                    + " [<secret-file>], got "
                    + arguments.length
                    + " arguments"));
      }
      List<String> problems = new ArrayList<>();
      URI pageUri = readHttpUri("page url", arguments[0], problems);
      URI statsUri = readHttpUri("origin stats url", arguments[1], problems);
      int fans = readCount("fans", arguments[2], MOST_FANS, problems);
      int concurrentFans =
          readCount("concurrent fans", arguments[3], MOST_CONCURRENT_FANS, problems);
      String secret = "";
      if (arguments.length == 5) {
        try {
          secret = Files.readString(Path.of(arguments[4]), StandardCharsets.UTF_8).strip();
        } catch (IOException unreadable) {
          problems.add(
              "secret file '"
                  + arguments[4]
                  + "' cannot be read ("
                  + unreadable.getClass().getSimpleName()
                  + "); scripts/stack.sh up writes it");
        }
      }
      if (problems.isEmpty()) {
        return Result.success(new Settings(pageUri, statsUri, fans, concurrentFans, secret));
      }
      return Result.error(List.copyOf(problems));
    }

    private static URI readHttpUri(String what, String text, List<String> problems) {
      try {
        URI uri = new URI(text);
        if ("http".equals(uri.getScheme()) && uri.getHost() != null && uri.getPath() != null) {
          return uri;
        }
      } catch (URISyntaxException malformed) {
        // Reported below, with the text the reader typed.
      }
      problems.add(what + " must be an http:// URL with a host, got '" + text + "'");
      return URI.create("http://invalid.example/");
    }

    private static int readCount(String what, String text, int most, List<String> problems) {
      // Digits only, read as decimal, so "064" is 64 and "1e3" is refused.
      if (text.isEmpty()
          || text.length() > MOST_NUMBER_DIGITS
          || !text.chars().allMatch(Character::isDigit)) {
        problems.add(what + " must be a whole number in 1.." + most + ", got '" + text + "'");
        return 1;
      }
      int count = Integer.parseInt(text);
      if (count < 1 || count > most) {
        problems.add(what + " must be in 1.." + most + ", got " + count);
        return 1;
      }
      return count;
    }
  }
}
