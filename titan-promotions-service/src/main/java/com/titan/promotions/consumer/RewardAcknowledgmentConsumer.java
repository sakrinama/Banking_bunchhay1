package com.titan.promotions.consumer;

import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.repository.AppliedPromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class RewardAcknowledgmentConsumer {
    
    private final AppliedPromotionRepository appliedPromotionRepository;
    
    @KafkaListener(
        topics = "banking.rewards.acknowledgment",
        groupId = "promotions-reward-ack"
    )
    public void consumeAcknowledgment(Map<String, Object> ack) {
        String rewardEventId = (String) ack.get("rewardEventId");
        String status = (String) ack.get("status");
        
        appliedPromotionRepository.findByRewardEventId(rewardEventId).ifPresent(applied -> {
            if ("SUCCESS".equals(status)) {
                applied.setRewardStatus(AppliedPromotion.RewardStatus.DISBURSED);
                log.info("Reward disbursed for promotion {}", applied.getId());
            } else {
                applied.setRewardStatus(AppliedPromotion.RewardStatus.FAILED);
                log.error("Reward disbursement failed for promotion {}", applied.getId());
            }
            appliedPromotionRepository.save(applied);
        });
    }
}
