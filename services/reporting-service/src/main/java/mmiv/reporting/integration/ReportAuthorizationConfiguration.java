package mmiv.reporting.integration;
import java.util.UUID;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class ReportAuthorizationConfiguration implements WebMvcConfigurer {
    private final ReportAuthorization authorization;
    public ReportAuthorizationConfiguration(ReportAuthorization authorization){this.authorization=authorization;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
            String path=request.getRequestURI().substring(request.getContextPath().length());
            String[] parts=path.split("/");
            // Global listing exposes other owners' reports. Query an explicit authorized migration instead.
            if(parts.length<4) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A migration ID is required");
            try {authorization.require(UUID.fromString(parts[3]));}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid migration ID");}
            return true;
        }
    }).addPathPatterns("/api/reports","/api/reports/**");}
}
