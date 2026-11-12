package io.plainround.seatwave.cdn.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.cdn.load.EdgeLoadClient.LoadReport;
import io.plainround.seatwave.cdn.origin.OriginServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EdgeLoadClientTest {

  // A throwaway value of the secret's shape (64 hex digits); the stack generates a random one.
  private static final String SECRET = "c".repeat(64);
  private static final String PAGE =
      "<script src=\"/static/seat-map.0123456789ab.js\"></script><img src=\"/static/poster.0123456789ab.jpg\">";

  @TempDir Path folder;

  @Test
  void playsEveryFanWithThePageAndEachFileItNames() throws Exception {
    LoadReport report = runAgainstOrigin(20, 4);

    assertThat(report.requests()).isEqualTo(60);
    assertThat(report.failedRequests()).isZero();
    assertThat(report.originRequests()).isEqualTo(60);
    assertThat(report.originBodyBytes()).isEqualTo(20L * (PAGE.length() + 6 + 6));
    assertThat(report.bodyBytesReceived()).isEqualTo(report.originBodyBytes());
    assertThat(report.cacheStatuses()).containsExactlyEntriesOf(Map.of("none", 60L));
    assertThat(report.hitRatioText()).isEqualTo("none (no cache in front)");
  }

  @Test
  void withoutTheSecretEveryFanIsRefusedAndCountedAsFailed() throws Exception {
    Path secretFile = Files.writeString(folder.resolve("secret"), SECRET);
    OriginServer origin = origin();
    try (OriginServer.Running running = origin.serve(settings(SECRET))) {
      // The stats URL on another authority (localhost, not 127.0.0.1): the client sends the
      // secret to the counters but not to the fans, as it does through an edge.
      EdgeLoadClient.Settings settings =
          parsed(
              "http://localhost:" + running.port() + "/events/arena.html",
              "http://127.0.0.1:" + running.port() + "/_origin/stats",
              "5",
              "2",
              secretFile.toString());

      LoadReport report = run(settings);

      assertThat(report.requests()).isEqualTo(5);
      assertThat(report.failedRequests()).isEqualTo(5);
      assertThat(report.originRequests()).isZero();
      assertThat(origin.refusedCount()).isEqualTo(5);
    }
  }

  @Test
  void printsTheReportItMeasured() throws Exception {
    LoadReport report = runAgainstOrigin(2, 1);

    assertThat(report.lines()).hasSize(6).first().asString().startsWith("fans 2, requests 6 in ");
    assertThat(report.lines().get(3)).startsWith("origin requests 6 (3.0000 per fan)");
  }

  @Test
  void aHitRatioDividesAnswersAtTheEdgeByTheSameResponses() {
    LoadReport report = report(Map.of("HIT", 9L, "MISS", 1L), 10);

    assertThat(report.hitRatioText()).isEqualTo("90.000%");
  }

  @Test
  void reportsNoResponseDifferentlyFromNoCache() {
    assertThat(report(Map.of(), 0).hitRatioText())
        .isEqualTo("not measured: no response was received");
    assertThat(report(Map.of("none", 3L), 3).hitRatioText()).isEqualTo("none (no cache in front)");
  }

  @Test
  void reportsEveryProblemOfACommandLineTogether() {
    List<String> problems = problems("ftp://x/", "not a url", "0", "2000", "no-such-secret");

    assertThat(problems)
        .containsExactly(
            "page url must be an http:// URL with a host, got 'ftp://x/'",
            "origin stats url must be an http:// URL with a host, got 'not a url'",
            "fans must be in 1..1000000, got 0",
            "concurrent fans must be in 1..1024, got 2000",
            "secret file 'no-such-secret' cannot be read (NoSuchFileException);"
                + " scripts/stack.sh up writes it");
  }

  @Test
  void readsCountsWithLeadingZerosAsDecimalAndRefusesOtherText() {
    assertThat(
            parsed("http://127.0.0.1:1/", "http://127.0.0.1:1/_origin/stats", "020000", "064")
                .fans())
        .isEqualTo(20000);
    assertThat(problems("http://127.0.0.1:1/", "http://127.0.0.1:1/", "1e3", "-1"))
        .containsExactly(
            "fans must be a whole number in 1..1000000, got '1e3'",
            "concurrent fans must be a whole number in 1..1024, got '-1'");
  }

  @Test
  void reportsAWrongNumberOfArguments() {
    assertThat(problems("http://127.0.0.1:1/")).singleElement().asString().contains("got 1");
  }

  private LoadReport runAgainstOrigin(int fans, int atOnce) throws Exception {
    Path secretFile = Files.writeString(folder.resolve("secret"), SECRET + "\n");
    try (OriginServer.Running running = origin().serve(settings(SECRET))) {
      String base = "http://127.0.0.1:" + running.port();
      return run(
          parsed(
              base + "/events/arena.html",
              base + "/_origin/stats",
              Integer.toString(fans),
              Integer.toString(atOnce),
              secretFile.toString()));
    }
  }

  private static LoadReport run(EdgeLoadClient.Settings settings)
      throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return EdgeLoadClient.run(settings, client);
    }
  }

  private OriginServer origin() {
    return new OriginServer(
        Map.of(
            "/events/arena.html", utf8(PAGE),
            "/static/seat-map.0123456789ab.js", utf8("script"),
            "/static/poster.0123456789ab.jpg", utf8("poster")));
  }

  private OriginServer.Settings settings(String secret) {
    return new OriginServer.Settings(folder, 0, InetAddress.getLoopbackAddress(), secret);
  }

  private static LoadReport report(Map<String, Long> statuses, long requests) {
    return new LoadReport(1, requests, 1.0, 0, statuses, 0, 0, 0, 0, 0);
  }

  private static EdgeLoadClient.Settings parsed(String... arguments) {
    return EdgeLoadClient.Settings.parse(arguments)
        .orOnErrorThrow(problems -> new IllegalStateException("refused: " + problems));
  }

  private static List<String> problems(String... arguments) {
    List<List<String>> reported = new ArrayList<>();
    EdgeLoadClient.Settings.parse(arguments).peekError(reported::add);
    Assertions.assertThat(reported).as("a refusal").hasSize(1);
    return reported.get(0);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
