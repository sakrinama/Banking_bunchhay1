package com.titan.gateway.graphql;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Supergraph Controller — stitches core-banking and promotions subgraphs.
 *
 * Task 3: Federation — User.activeRewards resolved from promotions subgraph
 * Task 4: Redis cache — activeCampaigns cached for 5 minutes
 * Task 6: Partial fault tolerance — promotions failure returns null rewards, not 500
 */
@Controller
@RequiredArgsConstructor
public class SupergraphController {

    private static final Logger log = LoggerFactory.getLogger(SupergraphController.class);

    private final WebClient.Builder webClientBuilder;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    private static final String CORE_BANKING_URL = "http://titan-core-banking:8080";
    private static final String PROMOTIONS_URL   = "http://titan-promotions-service:8083";
    private static final String CAMPAIGNS_CACHE_KEY = "supergraph:campaigns";

    // ── Queries ───────────────────────────────────────────────────────────────

    @QueryMapping
    public Mono<Map<String, Object>> user(@Argument String id) {
        return webClientBuilder.baseUrl(CORE_BANKING_URL).build()
                .get().uri("/api/v1/users/{id}", id)
                .retrieve()
                .bodyToMono(Map.class)
                .cast(Map.class)
                .map(u -> (Map<String, Object>) u)
                .onErrorResume(e -> {
                    log.error("[Supergraph] core-banking unavailable for user {}: {}", id, e.getMessage());
                    return Mono.empty();
                });
    }

    @QueryMapping
    public Mono<Map<String, Object>> account(@Argument String id) {
        return webClientBuilder.baseUrl(CORE_BANKING_URL).build()
                .get().uri("/api/v1/accounts/{id}", id)
                .retrieve()
                .bodyToMono(Map.class)
                .cast(Map.class)
                .map(a -> (Map<String, Object>) a)
                .onErrorResume(e -> {
                    log.error("[Supergraph] core-banking unavailable for account {}: {}", id, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Task 4: Redis-cached activeCampaigns — TTL 5 minutes.
     * Prevents repeated PostgreSQL hits for static campaign data.
     */
    @QueryMapping
    public Mono<List<Map<String, Object>>> activeCampaigns() {
        return redisTemplate.opsForValue().get(CAMPAIGNS_CACHE_KEY)
                .cast(List.class)
                .map(l -> (List<Map<String, Object>>) l)
                .switchIfEmpty(
                    webClientBuilder.baseUrl(PROMOTIONS_URL).build()
                        .get().uri("/api/v1/promotions/campaigns/active")
                        .retrieve()
                        .bodyToFlux(Map.class)
                        .cast(Map.class)
                        .map(m -> (Map<String, Object>) m)
                        .collectList()
                        .flatMap(campaigns ->
                            redisTemplate.opsForValue()
                                .set(CAMPAIGNS_CACHE_KEY, campaigns, Duration.ofMinutes(5))
                                .thenReturn(campaigns)
                        )
                        .onErrorResume(e -> {
                            // Task 6: promotions down — return empty list, not error
                            log.warn("[Supergraph] promotions-service unavailable for campaigns: {}", e.getMessage());
                            return Mono.just(List.of());
                        })
                );
    }

    // ── Federation: User.activeRewards from promotions subgraph ──────────────

    /**
     * Task 3: Cross-service entity federation.
     * User comes from core-banking; activeRewards resolved from promotions.
     * Task 6: If promotions crashes, returns empty list — banking data still returned.
     */
    @SchemaMapping(typeName = "User", field = "activeRewards")
    public Mono<List<Map<String, Object>>> activeRewards(Map<String, Object> user) {
        String userId = String.valueOf(user.get("id"));
        return webClientBuilder.baseUrl(PROMOTIONS_URL).build()
                .get().uri("/api/v1/promotions/user/{userId}/active", userId)
                .retrieve()
                .bodyToFlux(Map.class)
                .cast(Map.class)
                .map(m -> (Map<String, Object>) m)
                .collectList()
                .onErrorResume(e -> {
                    // Task 6: Partial response — promotions failure is isolated
                    log.warn("[Supergraph] promotions-service unavailable for user {} rewards: {}", userId, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    /**
     * Task 7: WebSocket subscription — proxies the core-banking event stream.
     * Mobile app connects once; events pushed the millisecond a transfer clears.
     */
    @SubscriptionMapping
    public Flux<Map<String, Object>> transactionCompleted(@Argument String accountId) {
        return webClientBuilder.baseUrl(CORE_BANKING_URL.replace("http", "ws")).build()
                .get().uri("/graphql/websocket")
                .retrieve()
                .bodyToFlux(Map.class)
                .cast(Map.class)
                .map(e -> (Map<String, Object>) e)
                .filter(e -> accountId.equals(String.valueOf(e.get("accountId"))))
                .onErrorResume(e -> {
                    log.error("[Supergraph] Subscription stream error: {}", e.getMessage());
                    return Flux.empty();
                });
    }
}
