package io.plainround.seatwave.cdn.assets;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.cdn.assets.AssetVersioner.BuildProblem;
import io.plainround.seatwave.cdn.assets.AssetVersioner.Manifest;
import io.plainround.seatwave.cdn.origin.HeaderPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetVersionerTest {

  private static final String SCRIPT = "console.log('seat map');\n";

  @TempDir Path folder;

  @Test
  void namesEveryAssetAfterTheFirstTwelveHexDigitsOfItsHash() throws IOException {
    writeSite("<script src=\"${asset:seat-map.js}\"></script>");

    Manifest manifest = built(build());

    String expected = "/static/seat-map." + AssetVersioner.contentHash(utf8(SCRIPT)) + ".js";
    assertThat(manifest.versionedPaths()).containsExactlyEntriesOf(Map.of("seat-map.js", expected));
    assertThat(Files.readString(dist().resolve(expected.substring(1)))).isEqualTo(SCRIPT);
    assertThat(HeaderPolicy.classify(expected)).isEqualTo(HeaderPolicy.ContentKind.VERSIONED_ASSET);
  }

  @Test
  void rendersEachPageWithTheVersionedPaths() throws IOException {
    writeSite("<script src=\"${asset:seat-map.js}\"></script>");

    built(build());

    assertThat(Files.readString(dist().resolve("events/arena.html")))
        .matches("<script src=\"/static/seat-map\\.[0-9a-f]{12}\\.js\"></script>");
  }

  @Test
  void writesTheManifestAsJsonSortedByName() throws IOException {
    writeSite("${asset:seat-map.js}");
    Files.writeString(folder.resolve("site/static/a-first.css"), "body{}");

    built(build());

    assertThat(Files.readString(dist().resolve("asset-manifest.json")))
        .startsWith("{\n  \"a-first.css\": \"/static/a-first.")
        .contains("\"seat-map.js\": \"/static/seat-map.")
        .endsWith("\"\n}\n");
  }

  @Test
  void refusesAPageThatNamesAMissingAssetAndWritesNothing() throws IOException {
    writeSite("<script src=\"${asset:missing.js}\"></script>");

    BuildProblem problem = refused(build());

    assertThat(problem).isEqualTo(new BuildProblem.MissingAsset("arena.html", "missing.js"));
    assertThat(dist()).doesNotExist();
  }

  @Test
  void refusesAnAssetNameThePolicyWouldNotRecognize() throws IOException {
    writeSite("page");
    Files.writeString(folder.resolve("site/static/Seat_Map.JS"), "x");

    assertThat(refused(build())).isEqualTo(new BuildProblem.BadAssetName("Seat_Map.JS"));
  }

  @Test
  void refusesASiteFolderThatDoesNotExist() throws IOException {
    Path missing = folder.resolve("no-site");

    Result<Manifest, BuildProblem> result = AssetVersioner.build(missing, dist());

    assertThat(refused(result)).isEqualTo(new BuildProblem.MissingSiteFolder(missing.toString()));
  }

  @Test
  void keepsThePreviousReleasesAssetsWhenTheContentChanges() throws IOException {
    writeSite("${asset:seat-map.js}");
    String firstPath = built(build()).versionedPaths().get("seat-map.js");
    Files.writeString(folder.resolve("site/static/seat-map.js"), "console.log('release 2');\n");

    String secondPath = built(build()).versionedPaths().get("seat-map.js");

    assertThat(secondPath).isNotEqualTo(firstPath);
    assertThat(dist().resolve(firstPath.substring(1))).exists();
    assertThat(dist().resolve(secondPath.substring(1))).exists();
  }

  @Test
  void copiesSharedLinksUnderTheirOwnNames() throws IOException {
    writeSite("page");
    Files.createDirectories(folder.resolve("site/share"));
    Files.writeString(folder.resolve("site/share/poster-arena.jpg"), "poster");

    built(build());

    assertThat(Files.readString(dist().resolve("share/poster-arena.jpg"))).isEqualTo("poster");
  }

  private Result<Manifest, BuildProblem> build() throws IOException {
    return AssetVersioner.build(folder.resolve("site"), dist());
  }

  private Path dist() {
    return folder.resolve("dist");
  }

  private void writeSite(String page) throws IOException {
    Files.createDirectories(folder.resolve("site/static"));
    Files.createDirectories(folder.resolve("site/events"));
    Files.writeString(folder.resolve("site/static/seat-map.js"), SCRIPT);
    Files.writeString(folder.resolve("site/events/arena.html"), page);
  }

  private static Manifest built(Result<Manifest, BuildProblem> result) {
    return result.orOnErrorThrow(problem -> new IllegalStateException("refused: " + problem));
  }

  private static BuildProblem refused(Result<Manifest, BuildProblem> result) {
    List<BuildProblem> problems = new ArrayList<>();
    result.peekError(problems::add);
    assertThat(problems).as("a refusal").hasSize(1);
    return problems.get(0);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
