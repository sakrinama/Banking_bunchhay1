package com.titan.promotions.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReferralGraphService {
    private final UserGraphRepository userGraphRepository;
    private static final BigDecimal[] TIER_PERCENTAGES = {
        new BigDecimal("0.05"), // Level 1: 5%
        new BigDecimal("0.03"), // Level 2: 3%
        new BigDecimal("0.02"), // Level 3: 2%
        new BigDecimal("0.01")  // Level 4+: 1%
    };
    
    public Map<Long, BigDecimal> calculateReferralRewards(Long accountId, BigDecimal transactionAmount) {
        Map<Long, BigDecimal> rewards = new HashMap<>();
        List<UserNode> ancestors = userGraphRepository.findAncestorChain(accountId);
        
        for (int i = 0; i < ancestors.size() && i < 10; i++) {
            BigDecimal percentage = i < TIER_PERCENTAGES.length ? 
                TIER_PERCENTAGES[i] : TIER_PERCENTAGES[TIER_PERCENTAGES.length - 1];
            BigDecimal reward = transactionAmount.multiply(percentage);
            rewards.put(ancestors.get(i).getAccountId(), reward);
        }
        
        log.info("Calculated {} referral rewards for account {}", rewards.size(), accountId);
        return rewards;
    }
    
    public void addReferral(Long referrerId, Long referredAccountId) {
        UserNode referrer = userGraphRepository.findByAccountId(referrerId)
            .orElseGet(() -> {
                UserNode node = new UserNode();
                node.setAccountId(referrerId);
                return userGraphRepository.save(node);
            });
        
        UserNode referred = new UserNode();
        referred.setAccountId(referredAccountId);
        referred = userGraphRepository.save(referred);
        
        referrer.getReferrals().add(referred);
        userGraphRepository.save(referrer);
        log.info("Added referral: {} -> {}", referrerId, referredAccountId);
    }
}
