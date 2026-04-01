package com.titan.gateway.graphql;

import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class SupergraphConfig {

    /**
     * Task 8: Max query depth = 5.
     * Blocks: user -> accounts -> transactions -> relatedUsers -> transactions (depth 5+)
     */
    @Bean
    public Instrumentation maxDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(5);
    }

    /**
     * Task 8: Max query complexity = 50.
     * Each field costs 1; lists cost n*1. Prevents DoS via massive list queries.
     */
    @Bean
    public Instrumentation maxComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(50);
    }

    /** Task 4: Reactive Redis template for Supergraph caching */
    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        RedisSerializationContext<String, Object> context = RedisSerializationContext
                .<String, Object>newSerializationContext(new StringRedisSerializer())
                .value(new GenericJackson2JsonRedisSerializer())
                .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
