package br.com.lolmatchmaking.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableCaching
@EnableRetry
@EnableScheduling
@EnableTransactionManagement
public class SpringBackendApplication {
    public static void main(String[] args) {
        System.setProperty("spring.output.ansi.enabled", "always");
        SpringApplication app = new SpringApplication(SpringBackendApplication.class);
        app.run(args);
    }
}
