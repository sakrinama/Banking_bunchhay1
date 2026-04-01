package com.titan.promotions.scheduler;

import com.titan.promotions.cache.CampaignCacheService;
import com.titan.promotions.model.Campaign;
import com.titan.promotions.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignExpiryScheduler {
    
    private final CampaignRepository campaignRepository;
    private final CampaignCacheService cacheService;
    
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void sweepExpiredCampaigns() {
        log.info("Starting campaign expiry sweep at 2:00 AM");
        
        LocalDateTime now = LocalDateTime.now();
        List<Campaign> expired = campaignRepository.findExpiredCampaigns(now);
        
        expired.forEach(campaign -> {
            campaign.setStatus(Campaign.CampaignStatus.COMPLETED);
            campaign.setUpdatedAt(now);
            campaignRepository.save(campaign);
            log.info("Campaign {} marked as COMPLETED", campaign.getCampaignCode());
        });
        
        if (!expired.isEmpty()) {
            cacheService.invalidateCache();
            log.info("Expired {} campaigns and cleared cache", expired.size());
        }
    }
}
