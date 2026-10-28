package com.peergrab.presentation;

import com.peergrab.presentation.auth.AuthInterceptor;
import com.peergrab.presentation.auth.InternalAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final InternalAuthInterceptor internalAuthInterceptor;
    private final List<String> corsOrigins;

    public WebConfig(AuthInterceptor authInterceptor,
                     InternalAuthInterceptor internalAuthInterceptor,
                     @Value("${peergrab.web.cors-origins:http://localhost:5173}") String corsOrigins) {
        this.authInterceptor = authInterceptor;
        this.internalAuthInterceptor = internalAuthInterceptor;
        this.corsOrigins = java.util.Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先校验内部 token（若配置），再走正常 Bearer 身份；两层都通过才允许内部端点。
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/api/internal/**");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登出用自身携带的 access 或 refresh 凭据鉴权，以便 access 过期后吊销 refresh。
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/health");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
