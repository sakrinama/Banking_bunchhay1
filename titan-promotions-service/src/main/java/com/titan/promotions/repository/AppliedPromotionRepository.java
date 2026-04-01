package com.titan.promotions.repository;

import com.titan.promotions.model.AppliedPromotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppliedPromotionRepository extends JpaRepository<AppliedPromotion, Long> {
    
    List<AppliedPromotion> findByAccountId(Long accountId);
    
    List<AppliedPromotion> findByTransactionId(Long transactionId);
    
    Optional<AppliedPromotion> findByRewardEventId(String rewardEventId);
}

