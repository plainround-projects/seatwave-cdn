package io.plainround.seatwave.cdn.origin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.cdn.http.Reply;
import io.plainround.seatwave.cdn.http.Upstream.Request;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginServerTest {

  private static final String HOST = "static.seatwave.example";
  // A throwaway value of the secret's shape (64 hex digits); the stack generates a random one.
  private static final String SECRET = "a".repeat(64);
  private static final String PAGE = "/events/arena.html";

  @TempDir Path distFolder;
  private HttpClient client;

  @BeforeEach
  void connect() {
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void disconnect() {
    client.close();
  }

  @Test
  void answersAKnownFileWithItsHeaderAndAStrongEntityTag() {
    OriginServer origin = new OriginServer(Map.of(PAGE, utf8("<html>page</html>")));

    Reply reply = origin.fetch(Request.get(HOST, PAGE));

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.header("cache-control")).contains(HeaderPolicy.cacheControlFor(PAGE));
    assertThat(reply.header("etag"))
        .hasValueSatisfying(tag -> assertThat(tag).matches("\"[0-9a-f]{16}\""));
    assertThat(reply.header("content-type")).contains("text/html; charset=utf-8");
    assertThat(origin.bodyBytesSent()).isEqualTo(17);
  }

  @Test
  void answersAMatchingIfNoneMatchWith304AndNoBody() {
    OriginServer origin = new OriginServer(Map.of(PAGE, utf8("<html>page</html>")));
    String entityTag = origin.fetch(Request.get(HOST, PAGE)).header("etag").orElseThrow();

    Reply revalidated =
        origin.fetch(Request.get(HOST, PAGE).withHeader("If-None-Match", entityTag));

    assertThat(revalidated.status()).isEqualTo(304);
    assertThat(revalidated.bodyLength()).isZero();
    assertThat(origin.requestCount()).isEqualTo(2);
  }

  @Test
  void answersHeadWithTheHeadersOfGetAndNoBody() {
    OriginServer origin = new OriginServer(Map.of(PAGE, utf8("<html>page</html>")));

    Reply head = origin.fetch(new Request("HEAD", HOST, PAGE, Map.of()));

    assertThat(head.status()).isEqualTo(200);
    assertThat(head.header("etag")).isPresent();
    assertThat(head.bodyLength()).isZero();
  }

  @Test
  void answersAnUnknownPathAndAPostWith404CarryingThePathsHeader() {
    OriginServer origin = new OriginServer(Map.of(PAGE, utf8("page")));

    Reply unknown = origin.fetch(Request.get(HOST, "/v1/holds/h-1"));
    Reply post = origin.fetch(new Request("POST", HOST, PAGE, Map.of()));

    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.header("cache-control")).contains("private, no-store");
    assertThat(post.status()).isEqualTo(404);
  }

  @Test
  void theSameBytesGiveTheSameEntityTagOnEveryOrigin() {
    OriginServer first = new OriginServer(Map.of(PAGE, utf8("page")));
    OriginServer second = new OriginServer(Map.of(PAGE, utf8("page")));

    assertThat(first.fetch(Request.get(HOST, PAGE)).header("etag"))
        .isEqualTo(second.fetch(Request.get(HOST, PAGE)).header("etag"));
  }

  @Test
  void readsADistFolderWithoutTheManifest() throws IOException {
    writeDist();

    OriginServer origin = OriginServer.fromDirectory(distFolder);

    assertThat(origin.fetch(Request.get(HOST, PAGE)).status()).isEqualTo(200);
    assertThat(origin.fetch(Request.get(HOST, "/asset-manifest.json")).status()).isEqualTo(404);
  }

  @Test
  void refusesARequestWithoutTheSecretAndCountsIt() throws Exception {
    writeDist();
    OriginServer origin = OriginServer.fromDirectory(distFolder);
    try (OriginServer.Running running = origin.serve(settings(SECRET))) {

      HttpResponse<String> direct = get(running, PAGE, "");
      HttpResponse<String> wrongSecret = get(running, PAGE, "b".repeat(64));

      assertThat(direct.statusCode()).isEqualTo(403);
      assertThat(wrongSecret.statusCode()).isEqualTo(403);
      assertThat(direct.headers().firstValue("cache-control")).contains("private, no-store");
      assertThat(origin.refusedCount()).isEqualTo(2);
      assertThat(origin.requestCount()).isZero();
    }
  }

  @Test
  void servesARequestThatCarriesTheSecret() throws Exception {
    writeDist();
    OriginServer origin = OriginServer.fromDirectory(distFolder);
    try (OriginServer.Running running = origin.serve(settings(SECRET))) {

      HttpResponse<String> throughTheTier = get(running, PAGE, SECRET);

      assertThat(throughTheTier.statusCode()).isEqualTo(200);
      assertThat(throughTheTier.body()).isEqualTo("<html>page</html>");
    }
  }

  @Test
  void reportsItsCountersWithoutCountingThatRequest() throws Exception {
    writeDist();
    OriginServer origin = OriginServer.fromDirectory(distFolder);
    try (OriginServer.Running running = origin.serve(settings(SECRET))) {
      get(running, PAGE, SECRET);

      HttpResponse<String> stats = get(running, "/_origin/stats", SECRET);

      assertThat(stats.body().strip()).isEqualTo("requests=1 bodyBytes=17 refused=0");
    }
  }

  @Test
  void aDeployReadsTheDistFolderAgainOnlyOnPost() throws Exception {
    writeDist();
    OriginServer origin = OriginServer.fromDirectory(distFolder);
    try (OriginServer.Running running = origin.serve(settings(""))) {
      Files.writeString(distFolder.resolve("events/new.html"), "new", StandardCharsets.UTF_8);

      HttpResponse<String> notYet = get(running, "/events/new.html", "");
      HttpResponse<String> wrongMethod = get(running, "/_origin/deploy", "");
      HttpResponse<String> deployed = post(running, "/_origin/deploy");
      HttpResponse<String> now = get(running, "/events/new.html", "");

      assertThat(notYet.statusCode()).isEqualTo(404);
      assertThat(wrongMethod.statusCode()).isEqualTo(405);
      assertThat(deployed.body().strip()).isEqualTo("deployed 2 files");
      assertThat(now.statusCode()).isEqualTo(200);
    }
  }

  @Test
  void answersHeadOverHttpWithoutABody() throws Exception {
    writeDist();
    OriginServer origin = OriginServer.fromDirectory(distFolder);
    try (OriginServer.Running running = origin.serve(settings(""))) {
      HttpRequest head =
          HttpRequest.newBuilder(uri(running, PAGE))
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .build();

      HttpResponse<String> response = client.send(head, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEmpty();
      assertThat(response.headers().firstValue("etag")).isPresent();
    }
  }

  @Test
  void reportsEveryProblemOfACommandLineTogether() throws IOException {
    Path notHex =
        Files.writeString(distFolder.resolve("secret"), "not hex", StandardCharsets.UTF_8);

    List<String> problems =
        problems(new String[] {"no-such-folder", "80", "10.0.0.1", notHex.toString()});

    assertThat(problems)
        .hasSize(4)
        .anyMatch(problem -> problem.contains("no-such-folder"))
        .anyMatch(problem -> problem.contains("1024..65535, got 80"))
        .anyMatch(problem -> problem.contains("'10.0.0.1'"))
        .anyMatch(problem -> problem.contains("64 lowercase hex digits"));
  }

  @Test
  void reportsAPortThatIsNotANumberAndAMissingSecretFile() {
    List<String> problems =
        problems(new String[] {distFolder.toString(), "21700x", "0.0.0.0", "no-such-secret"});

    assertThat(problems)
        .containsExactly(
            "port must be a number in 1024..65535, got '21700x'",
            "secret file 'no-such-secret' cannot be read (NoSuchFileException);"
                + " scripts/stack.sh up writes it");
  }

  @Test
  void reportsAWrongNumberOfArguments() {
    assertThat(problems(new String[] {"build/dist"}))
        .singleElement()
        .asString()
        .contains("got 1 arguments");
  }

  @Test
  void readsAPortWithALeadingZeroAsDecimal() throws IOException {
    Path secretFile = Files.writeString(distFolder.resolve("secret"), SECRET + "\n");

    Result<OriginServer.Settings, List<String>> parsed =
        OriginServer.Settings.parse(
            new String[] {distFolder.toString(), "021700", "0.0.0.0", secretFile.toString()});

    OriginServer.Settings settings =
        parsed.orOnErrorThrow(problems -> new IllegalStateException("refused: " + problems));
    assertThat(settings.port()).isEqualTo(21700);
    assertThat(settings.secret()).isEqualTo(SECRET);
  }

  @Test
  void neverListensBeyondTheLoopbackAddressWithoutASecret() {
    assertThatThrownBy(() ->
            new OriginServer.Settings(distFolder, 21700, InetAddress.ofLiteral("0.0.0.0"), ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a secret");
  }

  private List<String> problems(String[] arguments) {
    List<List<String>> reported = new ArrayList<>();
    OriginServer.Settings.parse(arguments).peekError(reported::add);
    assertThat(reported).as("a refusal").hasSize(1);
    return reported.get(0);
  }

  private OriginServer.Settings settings(String secret) {
    return new OriginServer.Settings(distFolder, 0, InetAddress.getLoopbackAddress(), secret);
  }

  private void writeDist() throws IOException {
    Files.createDirectories(distFolder.resolve("events"));
    Files.writeString(distFolder.resolve("events/arena.html"), "<html>page</html>");
    Files.writeString(distFolder.resolve("asset-manifest.json"), "{}");
  }

  private HttpResponse<String> get(OriginServer.Running running, String path, String secret)
      throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(running, path));
    if (!secret.isEmpty()) {
      request.header(OriginServer.SECRET_HEADER, secret);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(OriginServer.Running running, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(uri(running, path))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(OriginServer.Running running, String path) {
    return URI.create("http://127.0.0.1:" + running.port() + path);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
