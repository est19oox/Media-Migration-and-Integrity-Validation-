package mmiv.integration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

public class DiscoveryPermissionFilter extends OncePerRequestFilter {
  private final PermissionClient permissions;
  private final String destination;

  public DiscoveryPermissionFilter(PermissionClient permissions, String destination) {
    this.permissions = permissions;
    this.destination = destination;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.startsWith("/api/discovery/")
        && SecurityContextHolder.getContext().getAuthentication()
            instanceof JwtAuthenticationToken token) {
      try {
        permissions.require(
            token.getToken().getTokenValue(), token.getToken().getSubject(), destination, "WRITE");
      } catch (ResponseStatusException error) {
        response.setStatus(error.getStatusCode().value());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response
            .getWriter()
            .write(
                error.getStatusCode().value() == 403
                    ? "{\"error\":\"Destination permission required\"}"
                    : "{\"error\":\"Permission service unavailable\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
