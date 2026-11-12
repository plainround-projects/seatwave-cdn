package io.plainround.seatwave.cdn.http;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The response directives of one {@code Cache-Control} header (RFC 9111, Section 5.2.2), as a
 * shared cache reads them. Immutable and thread-safe.
 *
 * <p>Parsing fails safe: a header that cannot be read (a lifetime that is not a number) is treated
 * as {@code no-store}, because a cache that guesses may keep a private page for everyone.
 *
 * @param isPublic {@code public}: any cache may store it
 * @param isPrivate {@code private}: only the user's own browser may store it
 * @param isNoStore {@code no-store}: no cache may store it
 * @param isNoCache {@code no-cache}: a cache may store it but must revalidate before every use
 * @param isImmutable {@code immutable} (RFC 8246): a browser need not revalidate it on reload
 * @param maxAgeSeconds {@code max-age}, or {@link #ABSENT}
 * @param sharedMaxAgeSeconds {@code s-maxage}, or {@link #ABSENT}; shared caches only
 * @param staleWhileRevalidateSeconds {@code stale-while-revalidate} (RFC 5861), or {@link #ABSENT}
 */
public record CacheControl(
    boolean isPublic,
    boolean isPrivate,
    boolean isNoStore,
    boolean isNoCache,
    boolean isImmutable,
    long maxAgeSeconds,
    long sharedMaxAgeSeconds,
    long staleWhileRevalidateSeconds) {

  /** Marks a lifetime directive that the header does not carry. */
  public static final long ABSENT = -1;

  private static final CacheControl UNREADABLE =
      new CacheControl(false, false, true, false, false, ABSENT, ABSENT, ABSENT);

  /** Rejects negative lifetimes other than {@link #ABSENT}. */
  public CacheControl {
    for (long lifetimeSeconds :
        new long[] {maxAgeSeconds, sharedMaxAgeSeconds, staleWhileRevalidateSeconds}) {
      if (lifetimeSeconds < ABSENT) {
        throw new IllegalArgumentException("lifetime must be ABSENT or >= 0: " + lifetimeSeconds);
      }
    }
  }

  /**
   * Reads a header value; an empty value gives no directives, an unreadable one gives {@code
   * no-store}. Directive names are case-insensitive; unknown directives are ignored, as RFC 9111
   * requires.
   */
  public static CacheControl parse(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return new CacheControl(false, false, false, false, false, ABSENT, ABSENT, ABSENT);
    }
    boolean isPublic = false;
    boolean isPrivate = false;
    boolean isNoStore = false;
    boolean isNoCache = false;
    boolean isImmutable = false;
    long maxAge = ABSENT;
    long sharedMaxAge = ABSENT;
    long staleWhileRevalidate = ABSENT;
    for (String directive : headerValue.split(",")) {
      String[] nameAndValue = directive.trim().split("=", 2);
      String name = nameAndValue[0].trim().toLowerCase(Locale.ROOT);
      String value = nameAndValue.length == 2 ? nameAndValue[1].trim() : "";
      // private="Set-Cookie" (a field list) is still private: the stricter reading is the safe one.
      switch (name) {
        case "public" -> isPublic = true;
        case "private" -> isPrivate = true;
        case "no-store" -> isNoStore = true;
        case "no-cache" -> isNoCache = true;
        case "immutable" -> isImmutable = true;
        case "max-age", "s-maxage", "stale-while-revalidate" -> {
          Optional<Long> seconds = readSeconds(value);
          if (seconds.isEmpty()) {
            return UNREADABLE;
          }
          switch (name) {
            case "max-age" -> maxAge = seconds.get();
            case "s-maxage" -> sharedMaxAge = seconds.get();
            default -> staleWhileRevalidate = seconds.get();
          }
        }
        default -> {
          // An unknown extension changes nothing (RFC 9111, Section 5.2.3).
        }
      }
    }
    return new CacheControl(
        isPublic,
        isPrivate,
        isNoStore,
        isNoCache,
        isImmutable,
        maxAge,
        sharedMaxAge,
        staleWhileRevalidate);
  }

  /** True if a shared cache (a CDN edge, a shield, a proxy) must not store the response. */
  public boolean forbidsSharedStorage() {
    return isPrivate || isNoStore;
  }

  /**
   * How long a shared cache may serve the response without asking: {@code s-maxage} if present,
   * else {@code max-age}, else empty (no explicit lifetime). {@code no-cache} gives zero.
   */
  public Optional<Duration> sharedLifetime() {
    if (isNoCache) {
      return Optional.of(Duration.ZERO);
    }
    long seconds = sharedMaxAgeSeconds != ABSENT ? sharedMaxAgeSeconds : maxAgeSeconds;
    return seconds == ABSENT ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
  }

  /** How long after its lifetime a stale copy may still be served while it is refreshed. */
  public Duration staleWhileRevalidate() {
    return staleWhileRevalidateSeconds == ABSENT
        ? Duration.ZERO
        : Duration.ofSeconds(staleWhileRevalidateSeconds);
  }

  private static Optional<Long> readSeconds(String value) {
    // A delta-seconds value is digits only (RFC 9111, Section 1.2.2); anything else is unreadable.
    if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException tooLarge) {
      // RFC 9111 says a value too large to hold means "as long as possible"; this cache still
      // refuses it, because a lifetime nobody can type by accident is a misconfiguration.
      return Optional.empty();
    }
  }
}
