package mmiv.gateway.ratelimit;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Admission control after authentication/CSRF, before any backend is invoked. */
@Component
public class RequestRateLimitFilter implements WebFilter, Ordered {
  private final RedisRequestLimiter limiter;
  private final boolean enabled;
  private final int authLimit, uploadLimit, apiLimit;
  private static final int WINDOW_SECONDS = 60;

  public RequestRateLimitFilter(
      RedisRequestLimiter limiter,
      @Value("${mmiv.rate-limit.enabled:true}") boolean enabled,
      @Value("${mmiv.rate-limit.auth-per-minute:10}") int authLimit,
      @Value("${mmiv.rate-limit.uploads-per-minute:30}") int uploadLimit,
      @Value("${mmiv.rate-limit.api-per-minute:120}") int apiLimit) {
    if (authLimit < 1 || uploadLimit < 1 || apiLimit < 1)
      throw new IllegalArgumentException("Rate limits must be positive");
    this.limiter = limiter;
    this.enabled = enabled;
    this.authLimit = authLimit;
    this.uploadLimit = uploadLimit;
    this.apiLimit = apiLimit;
  }

  @Override
  public int getOrder() {
    return 0;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (!enabled || !(path.startsWith("/api/") || path.startsWith("/user-access/api/")))
      return chain.filter(exchange);
    boolean authentication = path.startsWith("/user-access/api/auth/");
    boolean upload =
        exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.POST
            && path.startsWith("/api/v1/transfers");
    String policy = authentication ? "authentication" : upload ? "upload" : "api";
    int limit = authentication ? authLimit : upload ? uploadLimit : apiLimit;
    // Do not trust client-supplied X-Forwarded-For. Deploying a proxy requires an
    // explicitly trusted forwarding configuration before changing this identity.
    var remote = exchange.getRequest().getRemoteAddress();
    String ip = remote == null ? "unknown" : remote.getAddress().getHostAddress();
    Mono<String> identity =
        authentication
            ? Mono.just("ip:" + ip)
            : exchange.getPrincipal().map(p -> "user:" + p.getName()).defaultIfEmpty("ip:" + ip);
    return identity.flatMap(
        key ->
            limiter
                .count(policy, key, WINDOW_SECONDS)
                .timeout(Duration.ofSeconds(2))
                .onErrorReturn(-1L)
                .flatMap(
                    count -> {
                      if (count < 0)
                        return reject(
                            exchange,
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "RATE_LIMIT_UNAVAILABLE",
                            3,
                            policy);
                      exchange
                          .getResponse()
                          .getHeaders()
                          .set("X-RateLimit-Limit", Integer.toString(limit));
                      exchange
                          .getResponse()
                          .getHeaders()
                          .set("X-RateLimit-Remaining", Long.toString(Math.max(0, limit - count)));
                      if (count > limit)
                        return reject(
                            exchange,
                            HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMIT_EXCEEDED",
                            WINDOW_SECONDS,
                            policy);
                      return chain.filter(exchange);
                    }));
  }

  private Mono<Void> reject(
      ServerWebExchange exchange, HttpStatus status, String code, int retryAfter, String policy) {
    io.micrometer.core.instrument.Metrics.counter(
            "mmiv.gateway.rate_limit.rejections", "policy", policy, "reason", code)
        .increment();
    var response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    response.getHeaders().set("Cache-Control", "no-store");
    response.getHeaders().set("Retry-After", Integer.toString(retryAfter));
    byte[] body =
        ("{\"error\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }
}
