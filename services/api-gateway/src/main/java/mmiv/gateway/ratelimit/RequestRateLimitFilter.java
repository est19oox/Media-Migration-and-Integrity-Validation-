package mmiv.gateway.ratelimit;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final boolean trustAlbXff;
  private final boolean trustCloudFrontXff;
  private final int authLimit, uploadLimit, apiLimit;
  private static final int WINDOW_SECONDS = 60;

  @Autowired
  public RequestRateLimitFilter(
      RedisRequestLimiter limiter,
      @Value("${mmiv.rate-limit.enabled:true}") boolean enabled,
      @Value("${mmiv.rate-limit.auth-per-minute:10}") int authLimit,
      @Value("${mmiv.rate-limit.uploads-per-minute:30}") int uploadLimit,
      @Value("${mmiv.rate-limit.api-per-minute:120}") int apiLimit,
      @Value("${MMIV_RATE_LIMIT_TRUST_ALB_XFF:false}") boolean trustAlbXff,
      @Value("${MMIV_RATE_LIMIT_TRUST_CLOUDFRONT_XFF:false}") boolean trustCloudFrontXff) {
    if (authLimit < 1 || uploadLimit < 1 || apiLimit < 1)
      throw new IllegalArgumentException("Rate limits must be positive");
    this.limiter = limiter;
    this.enabled = enabled;
    this.trustAlbXff = trustAlbXff;
    this.trustCloudFrontXff = trustCloudFrontXff;
    this.authLimit = authLimit;
    this.uploadLimit = uploadLimit;
    this.apiLimit = apiLimit;
  }

  // Preserve the existing constructor for direct tests and non-ALB deployments.
  public RequestRateLimitFilter(
      RedisRequestLimiter limiter, boolean enabled, int authLimit, int uploadLimit, int apiLimit) {
    this(limiter, enabled, authLimit, uploadLimit, apiLimit, false, false);
  }

  public RequestRateLimitFilter(
      RedisRequestLimiter limiter, boolean enabled, int authLimit, int uploadLimit, int apiLimit,
      boolean trustAlbXff) {
    this(limiter, enabled, authLimit, uploadLimit, apiLimit, trustAlbXff, false);
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
    var remote = exchange.getRequest().getRemoteAddress();
    String ip = remote == null ? "unknown" : remote.getAddress().getHostAddress();
    // ALB appends its socket peer. Behind CloudFront, that is the CloudFront
    // address; CloudFront first appends the viewer address. Never trust a
    // client-supplied prefix. Enable only with the CloudFront-only ALB rule.
    if (trustAlbXff) {
      String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
      if (xff != null) {
        String[] hops = xff.split(",", -1);
        int position = hops.length - (trustCloudFrontXff ? 2 : 1);
        if (position >= 0) {
          String trusted = hops[position].trim();
          if (trusted.length() <= 45 && trusted.matches("[0-9a-fA-F:.]+")) ip = trusted;
        }
      }
    }
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
