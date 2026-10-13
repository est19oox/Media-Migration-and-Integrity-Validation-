package mmiv.transfer.integration;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Return permission errors before reaching a file controller, including when an existing JWT loses its grant. */
public class DestinationPermissionFilter extends OncePerRequestFilter {
    private final MetadataClient metadata;
    public DestinationPermissionFilter(MetadataClient metadata) {this.metadata=metadata;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(path.equals("/admin.html") && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken) {
            try {
                if(!"ADMIN".equals(metadata.profile().role())) {
                    response.setStatus(302);response.setHeader("Location","/invite.html");response.setHeader("Cache-Control","no-store");return;
                }
            } catch(ResponseStatusException error) {response.sendError(error.getStatusCode().value(),"Account service unavailable");return;}
        }
        if ((path.equals("/api/v1/files") || path.startsWith("/api/v1/files/"))
                && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken) {
            try {
                String[] parts=path.split("/");
                if(parts.length<5) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid file request");
                String key=mmiv.transfer.files.FileIds.decode(parts[4]); int slash=key.indexOf('/');
                if(slash<1) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"No access to folder");
                metadata.requireFolder(key.substring(0,slash),"READ");
            }
            catch(IllegalArgumentException error) {response.sendError(400,"Invalid file ID");return;}
            catch(ResponseStatusException error) {
                response.setStatus(error.getStatusCode().value());response.setContentType("application/json");
                response.setHeader("Cache-Control","no-store");
                response.getWriter().write(error.getStatusCode().value()==403
                        ?"{\"error\":\"Destination permission required\"}":"{\"error\":\"Permission service unavailable\"}");return;
            }
        }
        chain.doFilter(request,response);
    }
}
