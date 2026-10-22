package mmiv.gateway.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RequestRateLimitFilterTest {
  @Test
  void authLimitUsesSocketAddressNotSpoofedForwardedHeader() {
    var redis = mock(RedisRequestLimiter.class);
    when(redis.count("authentication", "ip:127.0.0.1", 60)).thenReturn(Mono.just(11L));
    var filter = new RequestRateLimitFilter(redis, true, 10, 30, 120);
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/user-access/api/auth/login")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 1234))
                .header("X-Forwarded-For", "198.51.100.1"));
    var called = new AtomicBoolean();
    filter
        .filter(
            exchange,
            e -> {
              called.set(true);
              return Mono.empty();
            })
        .block(Duration.ofSeconds(3));
    assertFalse(called.get());
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    assertEquals("60", exchange.getResponse().getHeaders().getFirst("Retry-After"));
    verify(redis).count("authentication", "ip:127.0.0.1", 60);
  }

  @Test
  void trustedAlbUsesAppendedClientAddressInsteadOfSpoofedPrefix() {
    var redis = mock(RedisRequestLimiter.class);
    when(redis.count("authentication", "ip:203.0.113.8", 60)).thenReturn(Mono.just(1L));
    var filter = new RequestRateLimitFilter(redis, true, 10, 30, 120, true);
    var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/user-access/api/auth/login")
            .remoteAddress(new InetSocketAddress("10.0.1.9", 1234))
            .header("X-Forwarded-For", "198.51.100.1, 203.0.113.8"));
    filter.filter(exchange, e -> Mono.empty()).block(Duration.ofSeconds(3));
    verify(redis).count("authentication", "ip:203.0.113.8", 60);
    verify(redis, never()).count("authentication", "ip:198.51.100.1", 60);
  }

  @Test
  void trustedCloudFrontUsesViewerHopBeforeAlbAppendedHop() {
    var redis = mock(RedisRequestLimiter.class);
    when(redis.count("authentication", "ip:203.0.113.8", 60)).thenReturn(Mono.just(1L));
    var filter = new RequestRateLimitFilter(redis, true, 10, 30, 120, true, true);
    var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/user-access/api/auth/login")
            .remoteAddress(new InetSocketAddress("10.0.1.9", 1234))
            .header("X-Forwarded-For", "198.51.100.1, 203.0.113.8, 192.0.2.4"));
    filter.filter(exchange, e -> Mono.empty()).block(Duration.ofSeconds(3));
    verify(redis).count("authentication", "ip:203.0.113.8", 60);
    verify(redis, never()).count("authentication", "ip:198.51.100.1", 60);
  }

  @Test
  void redisFailureDoesNotInvokeBackend() {
    var redis = mock(RedisRequestLimiter.class);
    when(redis.count(anyString(), anyString(), eq(60)))
        .thenReturn(Mono.error(new IllegalStateException("offline")));
    var filter = new RequestRateLimitFilter(redis, true, 10, 30, 120);
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/access/me"));
    var called = new AtomicBoolean();
    filter
        .filter(
            exchange,
            e -> {
              called.set(true);
              return Mono.empty();
            })
        .block(Duration.ofSeconds(3));
    assertFalse(called.get());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
  }

  @Test
  void authenticatedUploadsShareUserBucketAndAllowThresholdRequest() {
    var redis = mock(RedisRequestLimiter.class);
    when(redis.count("upload", "user:alice", 60)).thenReturn(Mono.just(30L));
    var filter = new RequestRateLimitFilter(redis, true, 10, 30, 120);
    var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/transfers/folder"))
            .mutate()
            .principal(Mono.just(() -> "alice"))
            .build();
    var called = new AtomicBoolean();
    filter
        .filter(
            exchange,
            e -> {
              called.set(true);
              return Mono.empty();
            })
        .block(Duration.ofSeconds(3));
    assertTrue(called.get());
    assertEquals("0", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
  }

  @Test
  void staticPagesDoNotNeedRedis() {
    var redis = mock(RedisRequestLimiter.class);
    var filter = new RequestRateLimitFilter(redis, true, 10, 30, 120);
    filter
        .filter(
            MockServerWebExchange.from(MockServerHttpRequest.get("/pages/login.html")),
            e -> Mono.empty())
        .block(Duration.ofSeconds(3));
    verifyNoInteractions(redis);
  }
}
