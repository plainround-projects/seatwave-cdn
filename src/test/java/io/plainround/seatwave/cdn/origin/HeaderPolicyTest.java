package io.plainround.seatwave.cdn.origin;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.cdn.origin.HeaderPolicy.ContentKind;
import org.junit.jupiter.api.Test;

class HeaderPolicyTest {

  @Test
  void aVersionedAssetIsKeptAYearAndNeverRevalidated() {
    assertThat(HeaderPolicy.cacheControlFor("/static/seat-map.896d2ad8ee81.js"))
        .isEqualTo("public, max-age=31536000, immutable");
  }

  @Test
  void anEventPageIsKeptAMinuteAtTheEdgeAndZeroInBrowsers() {
    assertThat(HeaderPolicy.cacheControlFor("/events/arena.html"))
        .isEqualTo("public, max-age=0, s-maxage=60, stale-while-revalidate=30");
  }

  @Test
  void aSharedLinkIsKeptFiveMinutesInBrowsersAndAnHourAtTheEdge() {
    assertThat(HeaderPolicy.cacheControlFor("/share/poster-arena.jpg"))
        .isEqualTo("public, max-age=300, s-maxage=3600");
  }

  @Test
  void theCatalogAndSeatMapsAreAlwaysRevalidated() {
    assertThat(HeaderPolicy.classify("/v1/events")).isEqualTo(ContentKind.PUBLIC_DYNAMIC);
    assertThat(HeaderPolicy.classify("/v1/events/1/seats")).isEqualTo(ContentKind.PUBLIC_DYNAMIC);
    assertThat(HeaderPolicy.cacheControlFor("/v1/events/1/seat-changes/stream"))
        .isEqualTo("no-cache");
  }

  @Test
  void aHashOfElevenOrThirteenDigitsIsNotAVersionedName() {
    assertThat(HeaderPolicy.classify("/static/seat-map.896d2ad8ee8.js"))
        .isEqualTo(ContentKind.UNKNOWN);
    assertThat(HeaderPolicy.classify("/static/seat-map.896d2ad8ee816.js"))
        .isEqualTo(ContentKind.UNKNOWN);
  }

  @Test
  void aPersonalPrefixWinsOverEveryOtherRule() {
    for (String prefix : HeaderPolicy.PERSONAL_PREFIXES) {
      assertThat(HeaderPolicy.classify(prefix + "/events/arena.html"))
          .as(prefix)
          .isEqualTo(ContentKind.PERSONAL);
    }
  }
}
