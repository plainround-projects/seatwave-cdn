package io.plainround.seatwave.cdn.origin;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one place where SeatWave decides the {@code Cache-Control} of a response, from its path
 * alone. Every response the origin sends gets its header here; no handler sets its own. A path the
 * policy does not recognize is personal until proven otherwise ({@code private, no-store}): a new
 * endpoint someone forgot to classify is never cached at the edge (failing safe).
 *
 * <p>Stateless and thread-safe.
 */
public final class HeaderPolicy {

  /**
   * A versioned asset: {@code /static/<name>.<12 hex digits>.<extension>}, as {@code
   * AssetVersioner} writes it. Twelve hex digits are 48 bits of the SHA-256 of the content.
   */
  private static final Pattern VERSIONED_ASSET =
      Pattern.compile("^/static/[a-z0-9-]+\\.[0-9a-f]{12}\\.(js|css|jpg|png|webp|woff2)$");

  /** An event page, the HTML shell that names the versioned assets. */
  private static final Pattern EVENT_PAGE = Pattern.compile("^/events/[a-z0-9-]+\\.html$");

  /** A file whose name other sites link to (a poster in a social post), so it cannot change. */
  private static final Pattern SHARED_LINK = Pattern.compile("^/share/[a-z0-9-]+\\.(jpg|png)$");

  /** Public dynamic state: the catalog and the seat map (Modules 7 and 16), never stored. */
  private static final Pattern PUBLIC_DYNAMIC =
      Pattern.compile("^/v1/events(/[0-9]+(/seats|/seat-changes(/stream)?)?)?$");

  /**
   * Everything about one fan: holds and bookings (Module 7), tokens (Module 11): never in any
   * shared cache.
   */
  public static final List<String> PERSONAL_PREFIXES =
      List.of("/v1/holds", "/v1/bookings", "/v1/tokens");

  private HeaderPolicy() {}

  /** The kinds of SeatWave content, each with the one header it is always sent with. */
  public enum ContentKind {
    /** Same name, same bytes forever: a year, and no revalidation on reload. */
    VERSIONED_ASSET("public, max-age=31536000, immutable"),
    /** Browsers ask every time (cheap with an ETag); edges keep it 60 s, and 30 s more if stale. */
    EVENT_PAGE("public, max-age=0, s-maxage=60, stale-while-revalidate=30"),
    /** Five minutes in browsers, an hour at the edge; changed by purge, so changed rarely. */
    SHARED_LINK("public, max-age=300, s-maxage=3600"),
    /** Seat state changes 150 times a second per section (Module 16): always back to SeatWave. */
    PUBLIC_DYNAMIC("no-cache"),
    /** A fan's own data. */
    PERSONAL("private, no-store"),
    /** Not classified: treated as personal. */
    UNKNOWN("private, no-store");

    private final String cacheControl;

    ContentKind(String cacheControl) {
      this.cacheControl = cacheControl;
    }

    /** The {@code Cache-Control} value for this kind. */
    public String cacheControl() {
      return cacheControl;
    }
  }

  /** Classifies a path (without its query string). Never throws for a well-formed path. */
  public static ContentKind classify(String path) {
    Objects.requireNonNull(path, "path");
    // Personal first: no other rule may ever win for a fan's own data.
    if (PERSONAL_PREFIXES.stream().anyMatch(path::startsWith)) {
      return ContentKind.PERSONAL;
    }
    if (VERSIONED_ASSET.matcher(path).matches()) {
      return ContentKind.VERSIONED_ASSET;
    }
    if (EVENT_PAGE.matcher(path).matches()) {
      return ContentKind.EVENT_PAGE;
    }
    if (SHARED_LINK.matcher(path).matches()) {
      return ContentKind.SHARED_LINK;
    }
    if (PUBLIC_DYNAMIC.matcher(path).matches()) {
      return ContentKind.PUBLIC_DYNAMIC;
    }
    return ContentKind.UNKNOWN;
  }

  /** The {@code Cache-Control} header value for a path. */
  public static String cacheControlFor(String path) {
    return classify(path).cacheControl();
  }
}
