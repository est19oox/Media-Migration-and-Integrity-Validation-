package mmiv.gateway;

import java.util.Map;
import mmiv.events.EventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class RequestAuditFilter implements WebFilter {
  private final EventPublisher events;

  public RequestAuditFilter(EventPublisher events) {
    this.events = events;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (exchange.getRequest().getPath().value().startsWith("/actuator/"))
      return chain.filter(exchange);
    long started = System.nanoTime();
    return chain
        .filter(exchange)
        .then(
            Mono.fromRunnable(() -> audit(exchange, started, false))
                .subscribeOn(Schedulers.boundedElastic())
                .then())
        .onErrorResume(
            error ->
                Mono.fromRunnable(() -> audit(exchange, started, true))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.error(error)));
  }

  private void audit(ServerWebExchange exchange, long started, boolean failed) {
    try {
      events.audit(
          "HTTP_REQUEST",
          Map.of(
              "method",
              exchange.getRequest().getMethod().name(),
              "status",
              failed
                  ? 500
                  : exchange.getResponse().getStatusCode() == null
                      ? 200
                      : exchange.getResponse().getStatusCode().value(),
              "durationMs",
              (System.nanoTime() - started) / 1000000));
    } catch (RuntimeException ignored) {
    }
  }
}
