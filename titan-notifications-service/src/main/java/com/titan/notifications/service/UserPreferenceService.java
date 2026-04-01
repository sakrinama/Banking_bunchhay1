package com.titan.notifications.service;

import com.titan.notifications.model.UserPreference;
import com.titan.notifications.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserPreferenceService {
    
    private final UserPreferenceRepository repository;
    
    @Cacheable(value = "userPreferences", key = "#userId")
    public UserPreference getPreferences(String userId) {
        return repository.findById(userId)
                .orElseGet(() -> createDefaultPreference(userId));
    }
    
    public boolean canSendMarketing(String userId) {
        UserPreference pref = getPreferences(userId);
        return pref.isMarketingOptIn();
    }
    
    public boolean canSendTransactionAlert(String userId) {
        UserPreference pref = getPreferences(userId);
        return pref.isTransactionAlertsEnabled();
    }
    
    public String getPreferredLocale(String userId) {
        return getPreferences(userId).getPreferredLocale();
    }
    
    private UserPreference createDefaultPreference(String userId) {
        UserPreference pref = new UserPreference();
        pref.setUserId(userId);
        pref.setMarketingOptIn(true);
        pref.setTransactionAlertsEnabled(true);
        pref.setPreferredLocale("en");
        pref.setSmsNumber("+1234567890");
        pref.setEmail("user@example.com");
        return pref;
    }
}
