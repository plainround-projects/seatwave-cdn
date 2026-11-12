package io.plainround.seatwave.cdn.http;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Anything that answers an HTTP request: the origin, a shield, an edge. Each tier holds the next
 * one as its upstream, so an edge in front of a shield in front of the origin is three objects in a
 * line, exactly as Figure 1 draws them.
 */
@FunctionalInterface
public interface Upstream {

  /** Answers one request; never returns null. */
  Reply fetch(Request request);

  /**
   * One request: method, host, path with its query, and headers with lower-case names.
   *
   * @param method {@code GET}, {@code HEAD} or {@code POST}; nothing else is modeled
   * @param host the requested host, part of every cache key
   * @param pathAndQuery the path, starting with a slash, with any query string
   * @param headers the request headers
   */
  record Request(String method, String host, String pathAndQuery, Map<String, String> headers) {

    private static final Set<String> MODELED_METHODS = Set.of("GET", "HEAD", "POST");

    /** Validates every field at the boundary and copies the headers with lower-case names. */
    public Request {
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(host, "host");
      Objects.requireNonNull(pathAndQuery, "pathAndQuery");
      Objects.requireNonNull(headers, "headers");
      if (!MODELED_METHODS.contains(method)) {
        throw new IllegalArgumentException("method not modeled: " + method);
      }
      if (host.isBlank() || !pathAndQuery.startsWith("/")) {
        throw new IllegalArgumentException("host must be set and the path must start with /");
      }
      TreeMap<String, String> lowerCaseHeaders = new TreeMap<>();
      headers.forEach((name, value) -> lowerCaseHeaders.put(name.toLowerCase(Locale.ROOT), value));
      headers = Map.copyOf(lowerCaseHeaders);
    }

    /** A {@code GET} with no headers, the most common request in this module. */
    public static Request get(String host, String pathAndQuery) {
      return new Request("GET", host, pathAndQuery, Map.of());
    }

    /** The value of one header, by any capitalization of its name. */
    public Optional<String> header(String name) {
      return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The path without its query string. */
    public String path() {
      int queryStart = pathAndQuery.indexOf('?');
      return queryStart < 0 ? pathAndQuery : pathAndQuery.substring(0, queryStart);
    }

    /** The same request with one header added or replaced. */
    public Request withHeader(String name, String value) {
      TreeMap<String, String> changed = new TreeMap<>(headers);
      changed.put(name.toLowerCase(Locale.ROOT), value);
      return new Request(method, host, pathAndQuery, changed);
    }
  }
}
