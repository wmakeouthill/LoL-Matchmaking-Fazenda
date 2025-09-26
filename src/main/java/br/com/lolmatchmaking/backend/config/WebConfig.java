package br.com.lolmatchmaking.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.lang.NonNull;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        // ✅ CORREÇÃO CRÍTICA: NÃO usar "/**" que intercepta rotas da API
        // Ser mais específico com os recursos estáticos

        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600)
                .resourceChain(true);

        // Assets específicos do frontend
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(3600);

        // Arquivos específicos na raiz
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/favicon.ico")
                .setCachePeriod(3600);

        // ✅ IMPORTANTE: Arquivos JS e CSS específicos
        registry.addResourceHandler("/*.js")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);

        registry.addResourceHandler("/*.css")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(3600);

        // ✅ IMPORTANTE: index.html específico
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/index.html")
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        // ✅ REMOVIDO: ViewControllers que estavam conflitando com API e WebSocket
        // O FrontendController agora gerencia as rotas do frontend
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        // ✅ CORREÇÃO: Usar allowedOriginPatterns em vez de allowedOrigins com credentials
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false) // ✅ Desabilitar credentials para evitar erro CORS
                .maxAge(3600);

        // ✅ CORREÇÃO: WebSocket CORS sem credentials
        registry.addMapping("/ws/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(false);

        // ✅ CORREÇÃO: Rotas de compatibilidade sem credentials
        registry.addMapping("/lcu/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
