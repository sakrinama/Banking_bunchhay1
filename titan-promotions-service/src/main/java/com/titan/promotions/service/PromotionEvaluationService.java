package com.titan.promotions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.cache.CampaignCacheService;
import com.titan.promotions.engine.RuleEngine;
import com.titan.promotions.event.RewardGrantedEvent;
import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.idempotency.IdempotencyService;
import com.titan.promotions.lock.DistributedLockService;
import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.model.Campaign;
import com.titan.promotions.model.PromotionOutbox;
import com.titan.promotions.repository.AppliedPromotionRepository;
import com.titan.promotions.repository.CampaignRepository;
import com.titan.promotions.repository.PromotionOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class PromotionEvaluationService {
    
    private final CampaignCacheService cacheService;
    private final RuleEngine ruleEngine;
    private final IdempotencyService idempotencyService;
    private final DistributedLockService lockService;
    private final CampaignRepository campaignRepository;
    private final AppliedPromotionRepository appliedPromotionRepository;
    private final PromotionOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    
    private final Timer evaluationTimer;
    private final Counter promotionsAppliedCounter;
    private final Counter duplicateEventsCounter;
    
    public PromotionEvaluationService(
            CampaignCacheService cacheService,
            RuleEngine ruleEngine,
            IdempotencyService idempotencyService,
            DistributedLockService lockService,
            CampaignRepository campaignRepository,
            AppliedPromotionRepository appliedPromotionRepository,
            PromotionOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.cacheService = cacheService;
        this.ruleEngine = ruleEngine;
        this.idempotencyService = idempotencyService;
        this.lockService = lockService;
        this.campaignRepository = campaignRepository;
        this.appliedPromotionRepository = appliedPromotionRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        
        this.evaluationTimer = Timer.builder("promotion.evaluation.time")
            .description("Time taken to evaluate promotions")
            .register(meterRegistry);
        this.promotionsAppliedCounter = Counter.builder("promotion.applied.total")
            .description("Total promotions applied")
            .register(meterRegistry);
        this.duplicateEventsCounter = Counter.builder("promotion.duplicate.events")
            .description("Duplicate transaction events received")
            .register(meterRegistry);
    }
    
    @Transactional
    public void evaluateTransaction(TransactionCompletedEvent event) {
        evaluationTimer.record(() -> {
            if (!idempotencyService.markAsProcessed(event.getTransactionId())) {
                duplicateEventsCounter.increment();
                log.warn("Duplicate transaction {} - skipping", event.getTransactionId());
                return;
            }
            
            List<Campaign> campaigns = cacheService.getActiveCampaigns();
            
            for (Campaign campaign : campaigns) {
                if (ruleEngine.evaluate(campaign.getRuleExpression(), event)) {
                    applyPromotion(campaign, event);
                }
            }
        });
    }
    
    private void applyPromotion(Campaign campaign, TransactionCompletedEvent event) {
        lockService.executeWithLock(campaign.getId(), () -> {
            Campaign locked = campaignRepository.findById(campaign.getId())
                .orElseThrow(() -> new RuntimeException("Campaign not found"));
            
            if (locked.getQuotaLimit() != null && locked.getQuotaUsed() >= locked.getQuotaLimit()) {
                log.info("Campaign {} quota exhausted", campaign.getCampaignCode());
                return null;
            }
            
            AppliedPromotion applied = AppliedPromotion.builder()
                .transactionId(event.getTransactionId())
                .accountId(event.getAccountId())
                .campaignId(campaign.getId())
                .promotionType(campaign.getCampaignCode())
                .promotionAmount(campaign.getRewardAmount())
                .appliedAt(LocalDateTime.now())
                .description(campaign.getName())
                .rewardStatus(AppliedPromotion.RewardStatus.PENDING)
                .build();
            
            appliedPromotionRepository.save(applied);
            
            locked.setQuotaUsed(locked.getQuotaUsed() + 1);
            campaignRepository.save(locked);
            
            createRewardOutboxEvent(applied, event);
            
            promotionsAppliedCounter.increment();
            log.info("Applied campaign {} to transaction {}", campaign.getCampaignCode(), event.getTransactionId());
            
            return null;
        });
    }
    
    private void createRewardOutboxEvent(AppliedPromotion applied, TransactionCompletedEvent event) {
        try {
            RewardGrantedEvent rewardEvent = RewardGrantedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("REWARD_GRANTED")
                .eventVersion("1.0")
                .timestamp(LocalDateTime.now().toString())
                .correlationId(event.getCorrelationId())
                .accountId(applied.getAccountId())
                .transactionId(applied.getTransactionId())
                .campaignId(applied.getCampaignId())
                .rewardAmount(applied.getPromotionAmount())
                .currency(event.getCurrency())
                .description(applied.getDescription())
                .build();
            
            PromotionOutbox outbox = PromotionOutbox.builder()
                .eventId(rewardEvent.getEventId())
                .eventType("REWARD_GRANTED")
                .payload(objectMapper.writeValueAsString(rewardEvent))
                .status(PromotionOutbox.OutboxStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
            
            outboxRepository.save(outbox);
            
            applied.setRewardEventId(rewardEvent.getEventId());
            applied.setRewardStatus(AppliedPromotion.RewardStatus.DISPATCHED);
            appliedPromotionRepository.save(applied);
            
        } catch (Exception e) {
            log.error("Failed to create outbox event for promotion {}", applied.getId(), e);
            throw new RuntimeException("Outbox creation failed", e);
        }
    }
}
