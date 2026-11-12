package io.plainround.seatwave.cdn.assets;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The build step of Figure 4: it renames every file under {@code site/static} to {@code
 * <name>.<hash>.<extension>}, where the hash is the first 12 hex digits of the SHA-256 of its
 * bytes; writes {@code asset-manifest.json} (original name to versioned path); and writes every
 * page under {@code site/events} with each {@code ${asset:<name>}} replaced by its versioned path.
 *
 * <p>It checks everything before it writes anything, so a failed build leaves the dist folder as it
 * was. It never deletes a file in dist: the previous release's assets stay, so pages still cached
 * anywhere keep working (Figure 2). Stateless and thread-safe.
 */
public final class AssetVersioner {

  private static final int HASH_HEX_DIGITS = 12;
  private static final Pattern ASSET_NAME =
      Pattern.compile("^([a-z0-9-]+)\\.(js|css|jpg|png|webp|woff2)$");
  private static final Pattern ASSET_REFERENCE = Pattern.compile("\\$\\{asset:([a-z0-9.-]+)\\}");
  private static final int EXIT_BUILD_PROBLEM = 2;

  private AssetVersioner() {}

  /** Why a build was refused. Expected failures of the input, not bugs. */
  public sealed interface BuildProblem {
    /** A page names an asset that is not under {@code site/static}. */
    record MissingAsset(String page, String assetName) implements BuildProblem {}

    /** A file under {@code site/static} has a name the policy would not recognize when hashed. */
    record BadAssetName(String fileName) implements BuildProblem {}

    /** The site folder does not exist, so there is nothing to build (a typo in the path). */
    record MissingSiteFolder(String folder) implements BuildProblem {}
  }

  /**
   * What one build wrote.
   *
   * @param versionedPaths original asset name to its versioned URL path, sorted by name
   * @param pages the page paths written
   */
  public record Manifest(Map<String, String> versionedPaths, List<String> pages) {
    /** Copies both collections. */
    public Manifest {
      versionedPaths = Map.copyOf(versionedPaths);
      pages = List.copyOf(pages);
    }
  }

  /**
   * Builds {@code distFolder} from {@code siteFolder}; returns the manifest, or the first problem
   * found, in which case nothing was written. Throws {@link IOException} only for disk failures.
   */
  public static Result<Manifest, BuildProblem> build(Path siteFolder, Path distFolder)
      throws IOException {
    Objects.requireNonNull(siteFolder, "siteFolder");
    Objects.requireNonNull(distFolder, "distFolder");
    if (!Files.isDirectory(siteFolder)) {
      return Result.error(new BuildProblem.MissingSiteFolder(siteFolder.toString()));
    }
    TreeMap<String, String> versionedPaths = new TreeMap<>();
    // Insertion order is write order: assets, shared files and the manifest, then pages.
    LinkedHashMap<Path, byte[]> outputs = new LinkedHashMap<>();
    LinkedHashMap<Path, byte[]> pageOutputs = new LinkedHashMap<>();
    for (Path asset : listFiles(siteFolder.resolve("static"))) {
      String fileName = asset.getFileName().toString();
      Matcher nameParts = ASSET_NAME.matcher(fileName);
      if (!nameParts.matches()) {
        return Result.error(new BuildProblem.BadAssetName(fileName));
      }
      byte[] bytes = Files.readAllBytes(asset);
      String versionedName =
          nameParts.group(1) + "." + contentHash(bytes) + "." + nameParts.group(2);
      versionedPaths.put(fileName, "/static/" + versionedName);
      outputs.put(distFolder.resolve("static").resolve(versionedName), bytes);
    }
    List<String> pages = new ArrayList<>();
    for (Path page : listFiles(siteFolder.resolve("events"))) {
      String pageName = page.getFileName().toString();
      String template = Files.readString(page, StandardCharsets.UTF_8);
      Matcher references = ASSET_REFERENCE.matcher(template);
      StringBuilder rendered = new StringBuilder();
      while (references.find()) {
        String versionedPath = versionedPaths.get(references.group(1));
        if (versionedPath == null) {
          return Result.error(new BuildProblem.MissingAsset(pageName, references.group(1)));
        }
        references.appendReplacement(rendered, Matcher.quoteReplacement(versionedPath));
      }
      references.appendTail(rendered);
      pageOutputs.put(distFolder.resolve("events").resolve(pageName), bytesOf(rendered.toString()));
      pages.add("/events/" + pageName);
    }
    for (Path shared : listFiles(siteFolder.resolve("share"))) {
      // Names other sites link to are copied as they are: they change only by purge (Section 5e).
      outputs.put(
          distFolder.resolve("share").resolve(shared.getFileName()), Files.readAllBytes(shared));
    }
    outputs.put(distFolder.resolve("asset-manifest.json"), bytesOf(manifestJson(versionedPaths)));
    // Assets first, pages last: a page must never reach the origin before the files it names.
    outputs.putAll(pageOutputs);
    for (Map.Entry<Path, byte[]> output : outputs.entrySet()) {
      Files.createDirectories(output.getKey().getParent());
      Files.write(output.getKey(), output.getValue());
    }
    return Result.success(new Manifest(versionedPaths, pages));
  }

  /** Runs one build. Arguments: site folder, dist folder. Exit status 2 on a build problem. */
  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 2) {
      System.err.println(
          "settings: usage: AssetVersioner <site-folder> <dist-folder>, got "
              + arguments.length
              + " arguments");
      System.exit(EXIT_BUILD_PROBLEM);
    }
    Result<Manifest, BuildProblem> built = build(Path.of(arguments[0]), Path.of(arguments[1]));
    built.ifSuccess(manifest -> {
      manifest.versionedPaths().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> System.out.println(entry.getKey() + " -> " + entry.getValue()));
      manifest.pages().forEach(page -> System.out.println("page " + page));
    });
    if (built.isError()) {
      built.peekError(problem -> System.out.println("build refused: " + problem));
      System.exit(EXIT_BUILD_PROBLEM);
    }
  }

  /** The first 12 hex digits of the SHA-256 of {@code bytes}: the version part of a name. */
  public static String contentHash(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      // 12 hex digits are 48 bits: two different files of one release share a name with a
      // chance of about n squared over 2 to the 49th, under one in a billion for 100 files.
      return HexFormat.of().formatHex(digest).substring(0, HASH_HEX_DIGITS);
    } catch (NoSuchAlgorithmException missingAlgorithm) {
      throw new IllegalStateException("every JDK has SHA-256", missingAlgorithm);
    }
  }

  private static List<Path> listFiles(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(folder)) {
      // Sorted, so two builds of the same site write the same manifest byte for byte.
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }

  private static String manifestJson(Map<String, String> versionedPaths) {
    // Names and paths match ASSET_NAME and /static/..., so they never need JSON escaping.
    return versionedPaths.entrySet().stream()
        .map(entry -> "  \"" + entry.getKey() + "\": \"" + entry.getValue() + "\"")
        .collect(Collectors.joining(",\n", "{\n", "\n}\n"));
  }

  private static byte[] bytesOf(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
