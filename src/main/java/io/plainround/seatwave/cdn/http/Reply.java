package io.plainround.seatwave.cdn.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One HTTP response as the tiers of this module pass it on: status, headers with lower-case names,
 * and the body. Immutable and thread-safe: the body is a read-only buffer, so a 1 MB script cached
 * once is shared by every reply that carries it, and no caller can change the cached bytes.
 */
public record Reply(int status, Map<String, String> headers, ByteBuffer body) {

  private static final int LOWEST_STATUS = 100;
  private static final int HIGHEST_STATUS = 599;

  /** Validates the status and copies the headers with lower-case names. */
  public Reply {
    if (status < LOWEST_STATUS || status > HIGHEST_STATUS) {
      throw new IllegalArgumentException("status out of range: " + status);
    }
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(body, "body");
    TreeMap<String, String> lowerCaseHeaders = new TreeMap<>();
    headers.forEach((name, value) -> lowerCaseHeaders.put(name.toLowerCase(Locale.ROOT), value));
    headers = Map.copyOf(lowerCaseHeaders);
    body = body.asReadOnlyBuffer();
  }

  /** A reply whose body is a copy of {@code bodyBytes}, taken once. */
  public static Reply of(int status, Map<String, String> headers, byte[] bodyBytes) {
    return new Reply(status, headers, ByteBuffer.wrap(bodyBytes.clone()));
  }

  /** A read-only view of the body with its own position, so readers never disturb each other. */
  @Override
  public ByteBuffer body() {
    return body.duplicate();
  }

  /** The body's length in bytes. */
  public int bodyLength() {
    return body.remaining();
  }

  /** A copy of the body, for writing to a socket. */
  public byte[] bodyBytes() {
    byte[] copy = new byte[body.remaining()];
    body.duplicate().get(copy);
    return copy;
  }

  /** The body read as UTF-8 text, for pages and scripts. */
  public String bodyText() {
    return StandardCharsets.UTF_8.decode(body.duplicate()).toString();
  }

  /** The value of one header, by any capitalization of its name. */
  public Optional<String> header(String name) {
    return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
  }

  /** The {@code Cache-Control} directives; no header means no directives. */
  public CacheControl cacheControl() {
    return CacheControl.parse(header("cache-control").orElse(""));
  }

  /** The same reply with one header added or replaced; the body is shared, not copied. */
  public Reply withHeader(String name, String value) {
    TreeMap<String, String> changed = new TreeMap<>(headers);
    changed.put(name.toLowerCase(Locale.ROOT), value);
    return new Reply(status, changed, body);
  }
}
