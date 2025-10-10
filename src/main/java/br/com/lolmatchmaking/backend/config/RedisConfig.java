package br.com.lolmatchmaking.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configuração do Redis para cache distribuído e locks.
 * 
 * Resolve o problema de inconsistência de cache com múltiplas instâncias
 * substituindo ConcurrentHashMap por Redis distribuído.
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean redisSsl;

    /**
     * Configuração customizada do Redisson para Upstash
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        log.info("🔧 Configurando RedissonClient...");
        log.info("📡 Redis Host: {}", redisHost);
        log.info("📡 Redis Port: {}", redisPort);
        log.info("🔒 Redis SSL: {}", redisSsl);

        Config config = new Config();

        String address = (redisSsl ? "rediss://" : "redis://") + redisHost + ":" + redisPort;
        log.info("🌐 Redis Address: {}", address);

        config.useSingleServer()
                .setAddress(address)
                .setPassword(redisPassword.isEmpty() ? null : redisPassword)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500)
                .setKeepAlive(true);

        RedissonClient client = Redisson.create(config);

        log.info("✅ RedissonClient configurado com sucesso");

        return client;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        log.info("🔧 Configurando RedisTemplate...");

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // ObjectMapper com suporte a tipos Java 8+ (LocalDateTime, etc)
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Configurar serializadores
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);

        // Key serialization
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value serialization
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();

        log.info("✅ RedisTemplate configurado com sucesso");

        return template;
    }
}
