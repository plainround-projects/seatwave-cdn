package io.plainround.seatwave.cdn.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.cdn.http.Upstream.Request;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplyAndRequestTest {

  @Test
  void aReplyFindsHeadersByAnyCapitalization() {
    Reply reply = Reply.of(200, Map.of("Cache-Control", "no-cache"), new byte[0]);

    assertThat(reply.header("CACHE-control")).contains("no-cache");
    assertThat(reply.cacheControl().isNoCache()).isTrue();
  }

  @Test
  void aReplyBodyCannotBeChangedThroughItsBuffer() {
    Reply reply = Reply.of(200, Map.of(), "page".getBytes(StandardCharsets.UTF_8));

    ByteBuffer body = reply.body();

    assertThatThrownBy(() -> body.put((byte) 'x')).isInstanceOf(ReadOnlyBufferException.class);
    assertThat(reply.bodyText()).isEqualTo("page");
    assertThat(reply.bodyLength()).isEqualTo(4);
  }

  @Test
  void aReplyCopiesTheArrayItWasGiven() {
    byte[] bytes = "page".getBytes(StandardCharsets.UTF_8);
    Reply reply = Reply.of(200, Map.of(), bytes);

    bytes[0] = 'x';

    assertThat(reply.bodyBytes()).isEqualTo("page".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void withHeaderAddsOneHeaderAndKeepsTheBody() {
    Reply reply = Reply.of(200, Map.of("etag", "\"a\""), "page".getBytes(StandardCharsets.UTF_8));

    Reply labeled = reply.withHeader("X-Cache-Status", "HIT");

    assertThat(labeled.headers()).containsEntry("x-cache-status", "HIT").containsKey("etag");
    assertThat(labeled.bodyText()).isEqualTo("page");
    assertThat(reply.header("x-cache-status")).isEmpty();
  }

  @Test
  void aReplyRefusesAStatusOutsideHttpsRange() {
    assertThatThrownBy(() -> Reply.of(99, Map.of(), new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Reply.of(600, Map.of(), new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRequestSplitsItsPathFromItsQuery() {
    Request request = Request.get("static.seatwave.example", "/v1/events/1/seats?section=A");

    assertThat(request.path()).isEqualTo("/v1/events/1/seats");
    assertThat(request.pathAndQuery()).endsWith("?section=A");
  }

  @Test
  void aRequestKeepsHeadersWithLowerCaseNames() {
    Request request = Request.get("h", "/").withHeader("If-None-Match", "\"a\"");

    assertThat(request.header("if-none-match")).contains("\"a\"");
    assertThat(request.headers()).containsOnlyKeys("if-none-match");
  }

  @Test
  void aRequestRefusesAMethodItDoesNotModel() {
    assertThatThrownBy(() -> new Request("DELETE", "h", "/", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DELETE");
  }

  @Test
  void aRequestRefusesARelativePathOrABlankHost() {
    assertThatThrownBy(() -> Request.get("h", "events/arena.html"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Request.get(" ", "/")).isInstanceOf(IllegalArgumentException.class);
  }
}
