package io.plainround.seatwave.cdn.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The tiers' half of the invariant, checked in the configuration files themselves, so a change that
 * breaks it fails ./gradlew check without Docker: nothing under /v1/ is stored or answered from
 * cache, nothing lengthens or overrides the origin's lifetimes, credentialed requests bypass the
 * cache, the origin's secret reaches every proxied location, the lab paths stay private, and every
 * image and port is pinned. The stack test then checks the running tiers.
 */
class CdnTierConfigTest {

  private static final Path PROJECT = Path.of(System.getProperty("seatwave.project.directory"));
  private static final Path TEMPLATE = PROJECT.resolve("nginx/templates/cdn-tier.conf.template");
  private static final Path COMPOSE = PROJECT.resolve("compose.yaml");
  private static final Pattern LOCATION = Pattern.compile("(?s)location ([^{]+)\\{([^}]*)\\}");
  private static final Pattern PUBLISHED = Pattern.compile("\"127\\.0\\.0\\.1:(\\d+):(\\d+)\"");
  private static final Pattern PORT_LINE = Pattern.compile("(?m)^\\s+- \"([^\"]*:\\d+)\"$");
  // An image line, or the anchor that names the NGINX image once; an alias (*nginx-image) is not.
  private static final Pattern IMAGE =
      Pattern.compile("(?m)(?:^[ ]+image: |&nginx-image )([^\\s*][^\\s]*)$");
  private static final Pattern PINNED_IMAGE =
      Pattern.compile("^[a-z0-9.-]+:[0-9][A-Za-z0-9._-]*@sha256:[0-9a-f]{64}$");
  private static final int LOWEST_MODULE_PORT = 21_700;
  private static final int HIGHEST_MODULE_PORT = 21_799;

  @Test
  void theApiIsNeverStoredNorAnsweredFromCache() throws IOException {
    String apiLocation = location("/v1/ ");

    assertThat(apiLocation).contains("proxy_no_cache 1;").contains("proxy_cache_bypass 1;");
  }

  @Test
  void nothingLengthensOrOverridesTheOriginsLifetimes() throws IOException {
    String template = withoutComments(read(TEMPLATE));

    // proxy_cache_valid would give an answer without a lifetime one; proxy_ignore_headers
    // Cache-Control or Set-Cookie would store what the origin forbids.
    assertThat(template).doesNotContain("proxy_cache_valid").doesNotContain("proxy_ignore_headers");
  }

  @Test
  void aRequestWithCredentialsBypassesTheCache() throws IOException {
    String template = withoutComments(read(TEMPLATE));

    assertThat(template)
        .contains("proxy_no_cache $http_authorization;")
        .contains("proxy_cache_bypass $http_authorization;");
  }

  @Test
  void theSecretIsIncludedOnceAtServerLevelAndNoLocationDropsIt() throws IOException {
    String template = withoutComments(read(TEMPLATE));

    // A proxy_set_header inside a location replaces every one inherited from the server, so the
    // secret would silently stop reaching the origin from that location.
    assertThat(template).containsOnlyOnce("include /etc/seatwave-secret/origin-secret.conf;");
    for (String location : locations()) {
      assertThat(location).doesNotContain("proxy_set_header");
    }
  }

  @Test
  void theOriginsLabPathsAreNeverReachableThroughATier() throws IOException {
    assertThat(location("^~ /_origin/ ")).contains("return 404;");
  }

  @Test
  void theCacheKeyIsSchemeHostPathAndQuery() throws IOException {
    assertThat(withoutComments(read(TEMPLATE)))
        .contains("proxy_cache_key \"$scheme://$host$request_uri\";");
  }

  @Test
  void everyPortIsPublishedOnLoopbackInsideModule17sRange() throws IOException {
    String compose = read(COMPOSE);
    List<String> portLines = new ArrayList<>();
    PORT_LINE.matcher(compose).results().forEach(port -> portLines.add(port.group(1)));
    Matcher published = PUBLISHED.matcher(compose);
    List<Integer> hostPorts = new ArrayList<>();
    while (published.find()) {
      hostPorts.add(Integer.parseInt(published.group(1)));
    }

    assertThat(portLines).hasSize(3).allMatch(line -> line.startsWith("127.0.0.1:"));
    assertThat(hostPorts)
        .containsExactlyInAnyOrder(21_700, 21_701, 21_702)
        .allMatch(port -> port >= LOWEST_MODULE_PORT && port <= HIGHEST_MODULE_PORT);
  }

  @Test
  void everyImageIsPinnedByTagAndDigest() throws IOException {
    List<String> images = new ArrayList<>();
    IMAGE.matcher(read(COMPOSE)).results().forEach(image -> images.add(image.group(1)));

    assertThat(images).hasSize(3).allMatch(image -> PINNED_IMAGE.matcher(image).matches());
  }

  @Test
  void theOriginIsStartedWithItsSecretFile() throws IOException {
    String compose = read(COMPOSE);

    assertThat(compose)
        .contains("      - 0.0.0.0\n      - /etc/seatwave-secret/origin-secret\n")
        .contains("      - ./.secrets:/etc/seatwave-secret:ro\n");
  }

  private static String location(String prefix) throws IOException {
    return locations().stream()
        .filter(location -> location.startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no location " + prefix + "in " + TEMPLATE));
  }

  private static List<String> locations() throws IOException {
    List<String> found = new ArrayList<>();
    Matcher location = LOCATION.matcher(withoutComments(read(TEMPLATE)));
    while (location.find()) {
      found.add(location.group(1) + location.group(2));
    }
    return found;
  }

  private static String withoutComments(String text) {
    return text.replaceAll("(?m)#.*$", "");
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
