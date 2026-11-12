package io.plainround.seatwave.cdn.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.cdn.origin.OriginServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The module's invariant and the origin's restriction against the running stack (scripts/stack.sh
 * test): a static file is stored at the edge, nothing personal is, a credentialed request bypasses
 * the cache, and the origin refuses whatever did not come through a tier. Never part of ./gradlew
 * check. Each test uses its own cache key or counts only its own requests, so the tests pass in any
 * order and on a stack that has already served traffic.
 */
@Tag("stack")
class StackTest {

  private static final String ORIGIN = "http://127.0.0.1:21700";
  private static final String EDGE = "http://127.0.0.1:21701";
  private static final String SHIELD = "http://127.0.0.1:21702";
  private static final String SCRIPT = "/static/seat-map.896d2ad8ee81.js";
  private static final String PAGE = "/events/arena.html";
  private static final List<String> PERSONAL_PATHS =
      List.of("/v1/holds/h-1", "/v1/bookings", "/v1/tokens");
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Pattern STATS =
      Pattern.compile("requests=(\\d+) bodyBytes=\\d+ refused=(\\d+)");
  private static HttpClient client;
  private static String secret;

  @BeforeAll
  static void connect() throws IOException {
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    Path project = Path.of(System.getProperty("seatwave.project.directory"));
    secret =
        Files.readString(project.resolve(".secrets/origin-secret"), StandardCharsets.UTF_8).strip();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }

  @Test
  void oneFileThroughTheEdgeTwiceIsAMissThenAHitAndReachesTheOriginOnce() throws Exception {
    String freshKey = SCRIPT + "?stack-test=" + UUID.randomUUID();
    long before = originRequests();

    HttpResponse<String> first = get(EDGE + freshKey);
    HttpResponse<String> second = get(EDGE + freshKey);

    assertThat(first.headers().firstValue("x-cache-status")).contains("MISS");
    assertThat(first.headers().firstValue("x-shield-cache-status")).contains("MISS");
    assertThat(second.headers().firstValue("x-cache-status")).contains("HIT");
    assertThat(second.headers().firstValue("cache-control"))
        .contains("public, max-age=31536000, immutable");
    assertThat(originRequests() - before).isEqualTo(1);
  }

  @Test
  void everyPersonalPathBypassesBothTiersAndIsSentPrivate() throws Exception {
    long before = originRequests();

    for (String path : PERSONAL_PATHS) {
      for (int fan = 0; fan < 2; fan++) {
        HttpResponse<String> response = get(EDGE + path);

        assertThat(response.headers().firstValue("cache-control"))
            .as(path)
            .contains("private, no-store");
        assertThat(response.headers().firstValue("x-cache-status")).as(path).contains("BYPASS");
        assertThat(response.headers().firstValue("x-shield-cache-status"))
            .as(path)
            .contains("BYPASS");
      }
    }
    assertThat(originRequests() - before).isEqualTo(2L * PERSONAL_PATHS.size());
  }

  @Test
  void aRequestWithCredentialsIsNeverAnsweredFromTheEdgesCache() throws Exception {
    get(EDGE + PAGE);

    HttpResponse<String> withCredentials =
        client.send(
            HttpRequest.newBuilder(URI.create(EDGE + PAGE))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer fan-token")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(withCredentials.headers().firstValue("x-cache-status")).contains("BYPASS");
  }

  @Test
  void aRequestStraightToTheOriginWithoutTheSecretIsRefused() throws Exception {
    long refusedBefore = originRefused();

    HttpResponse<String> direct = get(ORIGIN + PAGE);

    assertThat(direct.statusCode()).isEqualTo(403);
    assertThat(direct.body()).doesNotContain("<html");
    assertThat(originRefused() - refusedBefore).isEqualTo(1);
  }

  @Test
  void aSecretSentByAClientIsReplacedByTheTier() throws Exception {
    String freshKey = PAGE + "?stack-test=" + UUID.randomUUID();

    HttpResponse<String> forged =
        client.send(
            HttpRequest.newBuilder(URI.create(EDGE + freshKey))
                .timeout(TIMEOUT)
                .header(OriginServer.SECRET_HEADER, "0".repeat(64))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(forged.statusCode()).isEqualTo(200);
    assertThat(forged.headers().firstValue("x-cache-status")).contains("MISS");
  }

  @Test
  void theOriginsLabPathsAreNotReachableThroughEitherTier() throws Exception {
    for (String tier : List.of(EDGE, SHIELD)) {
      assertThat(get(tier + "/_origin/stats").statusCode()).as(tier).isEqualTo(404);
    }
  }

  private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static long originRequests() throws IOException, InterruptedException {
    return stats().group(1).transform(Long::parseLong);
  }

  private static long originRefused() throws IOException, InterruptedException {
    return stats().group(2).transform(Long::parseLong);
  }

  private static Matcher stats() throws IOException, InterruptedException {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(ORIGIN + "/_origin/stats"))
                .timeout(TIMEOUT)
                .header(OriginServer.SECRET_HEADER, secret)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher numbers = STATS.matcher(response.body());
    assertThat(numbers.find()).as("origin stats: " + response.body()).isTrue();
    return numbers;
  }
}
