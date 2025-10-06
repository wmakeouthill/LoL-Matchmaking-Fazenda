package br.com.lolmatchmaking.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Configuração padrão para todos os caches
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats());

        // Caches específicos
        cacheManager.setCacheNames(java.util.List.of(
                "players",
                "player-by-summoner-name",
                "player-by-puuid",
                "riot-summoner-info",
                "riot-rank-info",
                "data-dragon-champions",
                "lcu-summoner-info"));

        return cacheManager;
    }

    @Bean("riotApiCacheManager")
    public CacheManager riotApiCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Cache mais longo para dados da Riot API (evitar rate limit)
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(15))
                .recordStats());

        return cacheManager;
    }

    @Bean("shortTermCacheManager")
    public CacheManager shortTermCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Cache curto para dados que mudam frequentemente
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofMinutes(1))
                .recordStats());

        return cacheManager;
    }
}
