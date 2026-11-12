package io.plainround.seatwave.cdn.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CacheControlTest {

  @Test
  void readsEveryDirectiveTheTiersActOn() {
    CacheControl directives =
        CacheControl.parse("public, max-age=0, s-maxage=60, stale-while-revalidate=30, immutable");

    assertThat(directives.isPublic()).isTrue();
    assertThat(directives.isImmutable()).isTrue();
    assertThat(directives.maxAgeSeconds()).isZero();
    assertThat(directives.sharedMaxAgeSeconds()).isEqualTo(60);
    assertThat(directives.staleWhileRevalidate()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void sharedMaxAgeBeatsMaxAgeForSharedCaches() {
    assertThat(CacheControl.parse("max-age=300, s-maxage=3600").sharedLifetime())
        .contains(Duration.ofSeconds(3600));
  }

  @Test
  void maxAgeIsTheSharedLifetimeWhenThereIsNoSharedMaxAge() {
    assertThat(CacheControl.parse("public, max-age=300").sharedLifetime())
        .contains(Duration.ofSeconds(300));
  }

  @Test
  void noCacheMeansALifetimeOfZero() {
    assertThat(CacheControl.parse("no-cache, max-age=600").sharedLifetime())
        .contains(Duration.ZERO);
  }

  @Test
  void noLifetimeDirectiveMeansNoExplicitLifetime() {
    assertThat(CacheControl.parse("public").sharedLifetime()).isEmpty();
    assertThat(CacheControl.parse("").sharedLifetime()).isEmpty();
  }

  @Test
  void directiveNamesAreCaseInsensitive() {
    CacheControl directives = CacheControl.parse("Private, NO-STORE");

    assertThat(directives.isPrivate()).isTrue();
    assertThat(directives.isNoStore()).isTrue();
    assertThat(directives.forbidsSharedStorage()).isTrue();
  }

  @Test
  void aLifetimeWithAUnitIsUnreadableAndTreatedAsNoStore() {
    CacheControl directives = CacheControl.parse("public, max-age=3600s");

    assertThat(directives.forbidsSharedStorage()).isTrue();
    assertThat(directives.isPublic()).isFalse();
  }

  @Test
  void aNegativeOrEmptyLifetimeIsUnreadable() {
    assertThat(CacheControl.parse("s-maxage=-1").forbidsSharedStorage()).isTrue();
    assertThat(CacheControl.parse("max-age=").forbidsSharedStorage()).isTrue();
  }

  @Test
  void aLifetimeTooLargeToHoldIsUnreadable() {
    assertThat(CacheControl.parse("max-age=99999999999999999999").forbidsSharedStorage()).isTrue();
  }

  @Test
  void unknownDirectivesChangeNothing() {
    assertThat(CacheControl.parse("public, max-age=60, must-understand").sharedLifetime())
        .contains(Duration.ofSeconds(60));
  }

  @Test
  void aStaleWindowIsZeroWhenAbsent() {
    assertThat(CacheControl.parse("max-age=60").staleWhileRevalidate()).isEqualTo(Duration.ZERO);
  }

  @Test
  void refusesANegativeLifetimeInTheConstructor() {
    assertThatThrownBy(() -> new CacheControl(false, false, false, false, false, -2, -1, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("-2");
  }
}
