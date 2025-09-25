package br.com.lolmatchmaking.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.lang.NonNull;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        // Servir arquivos estáticos do frontend Angular (já copiados pelo Maven para classpath:/static/)
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600)
                .resourceChain(true);

        // Assets específicos do frontend
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");

        // Arquivos estáticos específicos (CSS, JS, etc.)
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/favicon.ico");
    }

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        // Redirecionar todas as rotas não-API para o index.html (SPA)
        registry.addViewController("/")
                .setViewName("forward:/index.html");

        // Rotas específicas do frontend
        registry.addViewController("/queue")
                .setViewName("forward:/index.html");
        registry.addViewController("/draft")
                .setViewName("forward:/index.html");
        registry.addViewController("/match")
                .setViewName("forward:/index.html");
        registry.addViewController("/admin")
                .setViewName("forward:/index.html");
        registry.addViewController("/config")
                .setViewName("forward:/index.html");
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        registry.addMapping("/ws/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
