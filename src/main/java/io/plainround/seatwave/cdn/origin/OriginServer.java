package io.plainround.seatwave.cdn.origin;

import com.github.sviperll.result4j.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.cdn.http.Reply;
import io.plainround.seatwave.cdn.http.Upstream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SeatWave's static origin: it serves the files {@code AssetVersioner} wrote, each with the {@code
 * Cache-Control} of {@link HeaderPolicy} and a strong {@code ETag}, answers {@code If-None-Match}
 * with {@code 304}, and counts every request and body byte it sends. Thread-safe: the content is
 * swapped whole on deploy, and the counters are atomic.
 *
 * <p>Run it with {@code OriginServer <dist-folder> <port> [<bind-address> <secret-file>]}. It
 * listens on 127.0.0.1 unless told {@code 0.0.0.0} (the Compose stack, inside its private network),
 * and then it must be given a secret: every HTTP request without the header {@value #SECRET_HEADER}
 * carrying that secret is refused with {@code 403}, so only the CDN tiers, which add it, can reach
 * the origin.
 */
public final class OriginServer implements Upstream {

  /** The header the CDN tiers add to every request; a request without it never reaches a file. */
  public static final String SECRET_HEADER = "X-SeatWave-Origin-Secret";

  private static final int ENTITY_TAG_HEX_DIGITS = 16;
  private static final int HTTP_OK = 200;
  private static final int HTTP_NOT_MODIFIED = 304;
  private static final int HTTP_FORBIDDEN = 403;
  private static final int HTTP_NOT_FOUND = 404;
  private static final int HTTP_METHOD_NOT_ALLOWED = 405;
  private static final int NO_BODY = -1;
  private static final String STATS_PATH = "/_origin/stats";
  private static final String DEPLOY_PATH = "/_origin/deploy";
  private static final String MANIFEST_PATH = "/asset-manifest.json";
  private static final int EXIT_SETTINGS_PROBLEM = 2;
  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "js", "text/javascript; charset=utf-8",
          "css", "text/css; charset=utf-8",
          "jpg", "image/jpeg",
          "png", "image/png");

  private final UnaryOperator<String> cacheControlForPath;
  private final AtomicReference<Map<String, StoredFile>> deployed = new AtomicReference<>();
  private final AtomicLong requestCount = new AtomicLong();
  private final AtomicLong bodyBytesSent = new AtomicLong();
  private final AtomicLong refusedCount = new AtomicLong();

  /**
   * An origin serving {@code files}, keyed by URL path, with the headers of {@link HeaderPolicy}.
   */
  public OriginServer(Map<String, byte[]> files) {
    this(files, HeaderPolicy::cacheControlFor);
  }

  /**
   * An origin with another header rule, for the demos that replay what a different policy does
   * (Module 9's {@code public, max-age=300} on unversioned names). Production uses HeaderPolicy.
   */
  public OriginServer(Map<String, byte[]> files, UnaryOperator<String> cacheControlForPath) {
    this.cacheControlForPath = Objects.requireNonNull(cacheControlForPath, "cacheControlForPath");
    deploy(files);
  }

  /** Reads every file under {@code distFolder}, except the manifest, which is not a public URL. */
  public static OriginServer fromDirectory(Path distFolder) throws IOException {
    return new OriginServer(readFiles(distFolder));
  }

  /** Replaces all content at once, as a release does; old names that are not in it disappear. */
  public void deploy(Map<String, byte[]> files) {
    Objects.requireNonNull(files, "files");
    TreeMap<String, StoredFile> stored = new TreeMap<>();
    files.forEach(
        (path, bytes) -> stored.put(path, new StoredFile(bytes.clone(), entityTag(bytes))));
    deployed.set(Map.copyOf(stored));
  }

  /** Answers one request from the deployed files. Never throws for a valid request. */
  @Override
  public Reply fetch(Request request) {
    requestCount.incrementAndGet();
    String path = request.path();
    String cacheControl = cacheControlForPath.apply(path);
    StoredFile file = deployed.get().get(path);
    if (file == null || request.method().equals("POST")) {
      byte[] notFound = "not found\n".getBytes(StandardCharsets.UTF_8);
      bodyBytesSent.addAndGet(notFound.length);
      return Reply.of(HTTP_NOT_FOUND, Map.of("cache-control", cacheControl), notFound);
    }
    Map<String, String> headers =
        Map.of(
            "cache-control", cacheControl,
            "etag", file.entityTag(),
            "content-type", contentType(path));
    if (request.header("if-none-match").filter(file.entityTag()::equals).isPresent()) {
      // The cache already holds these bytes: 304 sends headers only (RFC 9110, Section 15.4.5).
      return Reply.of(HTTP_NOT_MODIFIED, headers, new byte[0]);
    }
    if (request.method().equals("HEAD")) {
      // HEAD: the same headers as GET, no body (RFC 9110, Section 9.3.2).
      return Reply.of(HTTP_OK, headers, new byte[0]);
    }
    bodyBytesSent.addAndGet(file.bytes().length);
    // The stored array is never changed after deploy, so the reply may share it read-only.
    return new Reply(HTTP_OK, headers, ByteBuffer.wrap(file.bytes()));
  }

  /** Requests answered so far (refused requests and the two lab paths are not counted). */
  public long requestCount() {
    return requestCount.get();
  }

  /** Body bytes sent so far: the origin's egress. */
  public long bodyBytesSent() {
    return bodyBytesSent.get();
  }

  /** Requests refused so far because they did not carry the secret. */
  public long refusedCount() {
    return refusedCount.get();
  }

  /**
   * Starts serving over HTTP with {@code settings}; the returned handle stops the server and its
   * threads when closed. Throws {@link IOException} if the port cannot be bound.
   */
  public Running serve(Settings settings) throws IOException {
    Objects.requireNonNull(settings, "settings");
    HttpServer server =
        HttpServer.create(new InetSocketAddress(settings.bindAddress(), settings.port()), 0);
    // One virtual thread per request: each one blocks only on its own socket.
    ExecutorService requestThreads = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(requestThreads);
    server.createContext("/", exchange -> handle(exchange, settings));
    server.start();
    return new Running(server, requestThreads);
  }

  /** Serves a dist folder over HTTP. Settings problems are printed together; exit status 2. */
  public static void main(String[] arguments) throws IOException {
    Result<Settings, List<String>> parsed = Settings.parse(arguments);
    if (parsed.isError()) {
      parsed.peekError(
          problems -> problems.forEach(problem -> System.err.println("settings: " + problem)));
      System.exit(EXIT_SETTINGS_PROBLEM);
    }
    Settings settings =
        parsed.orOnErrorThrow(problems -> new IllegalStateException("checked above"));
    OriginServer origin = fromDirectory(settings.distFolder());
    Running running = origin.serve(settings);
    System.out.printf(
        Locale.ROOT,
        "origin serving %d files on %s:%d, %s%n",
        origin.deployed.get().size(),
        settings.bindAddress().getHostAddress(),
        running.port(),
        settings.secret().isEmpty()
            ? "open to any client on this address"
            : "only to requests carrying " + SECRET_HEADER);
  }

  private void handle(HttpExchange exchange, Settings settings) throws IOException {
    try (exchange) {
      if (!carriesSecret(exchange, settings.secret())) {
        // Fail safe: no file, no counter, nothing but a refusal for a client that skipped the CDN.
        refusedCount.incrementAndGet();
        send(exchange, HTTP_FORBIDDEN, "forbidden: requests must come through the CDN tiers\n");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.equals(STATS_PATH)) {
        // Read by the load client to count origin requests; never cached, and not counted itself.
        send(
            exchange,
            HTTP_OK,
            String.format(
                Locale.ROOT,
                "requests=%d bodyBytes=%d refused=%d%n",
                requestCount(),
                bodyBytesSent(),
                refusedCount()));
        return;
      }
      if (path.equals(DEPLOY_PATH)) {
        deployFromFolder(exchange, settings.distFolder());
        return;
      }
      serveFile(exchange);
    }
  }

  private void deployFromFolder(HttpExchange exchange, Path distFolder) throws IOException {
    if (!exchange.getRequestMethod().equals("POST")) {
      send(exchange, HTTP_METHOD_NOT_ALLOWED, "deploy needs POST\n");
      return;
    }
    // The lab's "upload": read the dist folder again, as a release to object storage would add
    // the new files. AssetVersioner never deletes, so the old release's files are still there.
    Map<String, byte[]> files = readFiles(distFolder);
    deploy(files);
    send(exchange, HTTP_OK, String.format(Locale.ROOT, "deployed %d files%n", files.size()));
  }

  private void serveFile(HttpExchange exchange) throws IOException {
    Reply reply;
    try {
      reply = fetch(toRequest(exchange));
    } catch (IllegalArgumentException unmodeledRequest) {
      // A method or path this origin does not model: refuse it rather than guess.
      send(exchange, HTTP_NOT_FOUND, "not found\n");
      return;
    }
    reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    byte[] body = exchange.getRequestMethod().equals("HEAD") ? new byte[0] : reply.bodyBytes();
    exchange.sendResponseHeaders(reply.status(), body.length == 0 ? NO_BODY : body.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(body);
    }
  }

  private static boolean carriesSecret(HttpExchange exchange, String secret) {
    if (secret.isEmpty()) {
      return true;
    }
    String sent = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
    // A comparison whose time does not depend on how many leading characters match.
    return sent != null
        && MessageDigest.isEqual(
            sent.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange exchange, int status, String text) throws IOException {
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    // Lab answers and refusals are for one client only; no tier may keep them.
    exchange
        .getResponseHeaders()
        .set("Cache-Control", HeaderPolicy.ContentKind.UNKNOWN.cacheControl());
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(body);
    }
  }

  private static Map<String, byte[]> readFiles(Path distFolder) throws IOException {
    TreeMap<String, byte[]> files = new TreeMap<>();
    try (Stream<Path> paths = Files.walk(distFolder)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        String urlPath = "/" + distFolder.relativize(file).toString().replace('\\', '/');
        if (!urlPath.equals(MANIFEST_PATH)) {
          files.put(urlPath, Files.readAllBytes(file));
        }
      }
    }
    return files;
  }

  private static Request toRequest(HttpExchange exchange) {
    TreeMap<String, String> requestHeaders = new TreeMap<>();
    exchange
        .getRequestHeaders()
        .forEach((name, values) -> requestHeaders.put(name, String.join(",", values)));
    String host = exchange.getRequestHeaders().getFirst("Host");
    return new Request(
        exchange.getRequestMethod(),
        host == null ? "localhost" : host,
        exchange.getRequestURI().getRawPath(),
        requestHeaders);
  }

  private static String contentType(String path) {
    String extension = path.substring(path.lastIndexOf('.') + 1);
    return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
  }

  private static String entityTag(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      // A strong validator: the same bytes always give the same tag, on every origin machine.
      return "\"" + HexFormat.of().formatHex(digest).substring(0, ENTITY_TAG_HEX_DIGITS) + "\"";
    } catch (NoSuchAlgorithmException missingAlgorithm) {
      throw new IllegalStateException("every JDK has SHA-256", missingAlgorithm);
    }
  }

  private record StoredFile(byte[] bytes, String entityTag) {}

  /**
   * How the origin is served.
   *
   * @param distFolder the folder {@code AssetVersioner} wrote
   * @param port the TCP port; 0 lets the system pick one (tests only)
   * @param bindAddress 127.0.0.1 or 0.0.0.0
   * @param secret the value every request must carry in {@value #SECRET_HEADER}; empty for none,
   *     which only the loopback address allows
   */
  public record Settings(Path distFolder, int port, InetAddress bindAddress, String secret) {

    private static final int LOWEST_PORT = 1024;
    private static final int HIGHEST_PORT = 65535;
    // Nine digits always fit an int, so leading zeros never overflow the parse.
    private static final int MOST_PORT_DIGITS = 9;
    private static final Pattern SECRET_FORMAT = Pattern.compile("^[0-9a-f]{64}$");
    private static final String LOOPBACK = "127.0.0.1";
    private static final String EVERY_ADDRESS = "0.0.0.0";

    /** Rejects what no caller may build; {@link #parse} reports every problem of a command line. */
    public Settings {
      Objects.requireNonNull(distFolder, "distFolder");
      Objects.requireNonNull(bindAddress, "bindAddress");
      Objects.requireNonNull(secret, "secret");
      if (port < 0 || port > HIGHEST_PORT) {
        throw new IllegalArgumentException("port out of range: " + port);
      }
      if (secret.isEmpty() && !bindAddress.isLoopbackAddress()) {
        throw new IllegalArgumentException("a non-loopback origin needs a secret");
      }
    }

    /**
     * Reads {@code <dist-folder> <port> [<bind-address> <secret-file>]}; returns every problem
     * found, not only the first.
     */
    public static Result<Settings, List<String>> parse(String[] arguments) {
      Objects.requireNonNull(arguments, "arguments");
      if (arguments.length != 2 && arguments.length != 4) {
        return Result.error(
            List.of(
                "usage: OriginServer <dist-folder> <port> [<bind-address> <secret-file>], got "
                    + arguments.length
                    + " arguments"));
      }
      List<String> problems = new ArrayList<>();
      Path distFolder = Path.of(arguments[0]);
      if (!Files.isDirectory(distFolder)) {
        problems.add(
            "dist folder '" + arguments[0] + "' is not a folder; run ./gradlew versionAssets");
      }
      int port = readPort(arguments[1], problems);
      String address = arguments.length == 4 ? arguments[2] : LOOPBACK;
      // Only the two addresses the course uses; anything else is a typo, refused at start-up.
      if (!List.of(LOOPBACK, EVERY_ADDRESS).contains(address)) {
        problems.add("bind address must be 127.0.0.1 or 0.0.0.0, got '" + address + "'");
      }
      String secret = arguments.length == 4 ? readSecret(Path.of(arguments[3]), problems) : "";
      if (problems.isEmpty()) {
        return Result.success(
            new Settings(distFolder, port, InetAddress.ofLiteral(address), secret));
      }
      return Result.error(List.copyOf(problems));
    }

    private static int readPort(String text, List<String> problems) {
      // Digits only, read as decimal: "021700" is port 21700, never octal.
      if (text.isEmpty()
          || text.length() > MOST_PORT_DIGITS
          || !text.chars().allMatch(Character::isDigit)) {
        problems.add("port must be a number in 1024..65535, got '" + text + "'");
        return 0;
      }
      int port = Integer.parseInt(text);
      if (port < LOWEST_PORT || port > HIGHEST_PORT) {
        problems.add("port must be in 1024..65535, got " + port);
      }
      return port;
    }

    private static String readSecret(Path secretFile, List<String> problems) {
      String secret;
      try {
        // The file may end with a newline (openssl rand -hex writes one); nothing else is allowed.
        secret = Files.readString(secretFile, StandardCharsets.UTF_8).strip();
      } catch (IOException unreadable) {
        problems.add(
            "secret file '"
                + secretFile
                + "' cannot be read ("
                + unreadable.getClass().getSimpleName()
                + "); scripts/stack.sh up writes it");
        return "";
      }
      if (!SECRET_FORMAT.matcher(secret).matches()) {
        problems.add(
            "secret file '" + secretFile + "' must hold 64 lowercase hex digits (32 random bytes)");
      }
      return secret;
    }
  }

  /**
   * A running origin: its port, and {@link #close} to stop it.
   *
   * @param server the JDK server
   * @param requestThreads the executor of its request threads
   */
  public record Running(HttpServer server, ExecutorService requestThreads)
      implements AutoCloseable {

    /** The port the server listens on (the one the system picked, if the settings said 0). */
    public int port() {
      return server.getAddress().getPort();
    }

    /** Stops accepting, lets requests in progress finish for up to a second, stops the threads. */
    @Override
    public void close() {
      server.stop(1);
      requestThreads.close();
    }
  }
}
