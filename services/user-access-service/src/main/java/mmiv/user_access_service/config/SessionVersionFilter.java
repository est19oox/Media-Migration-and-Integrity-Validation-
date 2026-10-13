package mmiv.user_access_service.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import mmiv.user_access_service.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionVersionFilter extends OncePerRequestFilter {
  private final UserRepository users;

  public SessionVersionFilter(UserRepository users) {
    this.users = users;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.startsWith("/api/access/")
        || path.startsWith("/api/admin/")
        || path.equals("/api/auth/verification-account"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token) {
      if (token.getToken().getSubject() == null || token.getToken().getSubject().isBlank()) {
        chain.doFilter(request, response);
        return;
      }
      try {
        var user = users.findById(token.getToken().getSubject());
        Object claim = token.getToken().getClaim("ver");
        long version = claim instanceof Number number ? number.longValue() : claim == null ? 0 : -1;
        if (user.isPresent() && user.get().getAuthVersion() != version) {
          response.setStatus(401);
          response.setContentType("application/json");
          response.setHeader("Cache-Control", "no-store");
          response.getWriter().write("{\"error\":\"Session expired. Sign in again.\"}");
          return;
        }
      } catch (org.springframework.dao.DataAccessException error) {
        response.setStatus(503);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Account service unavailable\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
