package com.soumikseth.incidentinvestigator.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ExecutorConfig {

    /**
     * Tool calls block on HTTP. Virtual threads make a blocked call cheap enough
     * that per-call timeouts do not require a dedicated pooled thread per request.
     * Closing the executor at shutdown lets in-flight calls finish or time out.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService investigationToolExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
