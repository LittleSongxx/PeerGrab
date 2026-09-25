package com.peergrab.presentation;

import com.peergrab.presentation.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final List<String> corsOrigins;

    public WebConfig(AuthInterceptor authInterceptor,
                     @Value("${peergrab.web.cors-origins:http://localhost:5173}") String corsOrigins) {
        this.authInterceptor = authInterceptor;
        this.corsOrigins = java.util.Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
