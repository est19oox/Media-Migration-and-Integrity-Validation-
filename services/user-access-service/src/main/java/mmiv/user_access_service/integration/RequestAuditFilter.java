package mmiv.user_access_service.integration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import mmiv.events.EventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestAuditFilter extends OncePerRequestFilter {
  private final EventPublisher events;

  public RequestAuditFilter(EventPublisher events) {
    this.events = events;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long started = System.nanoTime();
    boolean failed = false;
    try {
      chain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException e) {
      failed = true;
      throw e;
    } finally {
      try {
        events.audit(
            "HTTP_REQUEST",
            Map.of(
                "method",
                request.getMethod(),
                "status",
                failed ? 500 : response.getStatus(),
                "durationMs",
                (System.nanoTime() - started) / 1000000));
      } catch (RuntimeException e) {
        logger.error("HTTP audit could not be persisted");
      }
    }
  }
}
