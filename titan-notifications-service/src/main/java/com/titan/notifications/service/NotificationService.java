package com.titan.notifications.service;

import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.strategy.ProviderStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {
    
    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("500.00");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ProviderStrategyService providerService;
    private final TemplateService templateService;
    private final UserPreferenceService preferenceService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;
    private final BatchingService batchingService;
    private final WebSocketNotificationService webSocketService;
    private final PredictiveDeliveryService predictiveDelivery;
    
    public void sendNotifications(TransactionCompletedEvent event) {
        log.info("=".repeat(80));
        log.info("📧 NOTIFICATION SERVICE - Processing Transaction Event");
        log.info("=".repeat(80));
        
        String userId = String.valueOf(event.getAccountId());
        String locale = event.getLocale() != null ? event.getLocale() : preferenceService.getPreferredLocale(userId);
        boolean isHighValue = event.getAmount().compareTo(HIGH_VALUE_THRESHOLD) > 0;
        boolean isMarketing = "PROMOTION".equals(event.getTransactionType());
        
        if (isMarketing && !preferenceService.canSendMarketing(userId)) {
            log.info("🚫 User {} opted out of marketing, dropping event silently", userId);
            return;
        }
        
        if (!isMarketing && !preferenceService.canSendTransactionAlert(userId)) {
            log.info("🚫 User {} disabled transaction alerts", userId);
            return;
        }
        
        // Task 1: Instant WebSocket push for real-time alerts
        Map<String, Object> wsPayload = buildTemplateData(event);
        webSocketService.pushToUser(userId, "TRANSACTION_ALERT", wsPayload);
        
        if (!rateLimiter.allowSms(userId)) {
            log.warn("🚫 SMS rate limit exceeded for user {}", userId);
            auditService.logRateLimited(String.valueOf(event.getTransactionId()), userId, "SMS", locale);
            return;
        }
        
        if (!rateLimiter.allowEmail(userId)) {
            log.warn("🚫 Email rate limit exceeded for user {}", userId);
            auditService.logRateLimited(String.valueOf(event.getTransactionId()), userId, "EMAIL", locale);
            return;
        }
        
        if (!isHighValue && !isMarketing) {
            processLowPriority(event, userId, locale);
        } else {
            processUrgent(event, userId, locale, isHighValue);
        }
        
        log.info("=".repeat(80));
        log.info("✅ Notification processing completed for transaction: {}", event.getTransactionId());
        log.info("=".repeat(80));
    }
    
    private void processUrgent(TransactionCompletedEvent event, String userId, String locale, boolean isHighValue) {
        Map<String, Object> data = buildTemplateData(event);
        String templateName = isHighValue ? "high_value_alert" : "transaction_alert";
        
        try {
            String smsBody = templateService.renderSms(templateName, data, locale);
            String emailBody = templateService.renderEmail(templateName, data, locale);
            
            providerService.sendSms("+1234567890", smsBody);
            providerService.sendEmail("user@example.com", emailBody);
            
            auditService.logAttempt(String.valueOf(event.getTransactionId()), userId, "SMS", "+1234567890",
                                   smsBody, "twilio", locale, true);
            auditService.logAttempt(String.valueOf(event.getTransactionId()), userId, "EMAIL", "user@example.com",
                                   emailBody, "sendgrid", locale, true);
        } catch (Exception e) {
            log.error("❌ Notification failed: {}", e.getMessage());
            auditService.logFailure(String.valueOf(event.getTransactionId()), userId, "SMS", "+1234567890",
                                   e.getMessage(), locale);
        }
    }
    
    private void processLowPriority(TransactionCompletedEvent event, String userId, String locale) {
        Map<String, Object> data = buildTemplateData(event);
        String smsBody = templateService.renderSms("transaction_alert", data, locale);
        String emailBody = templateService.renderEmail("transaction_alert", data, locale);
        
        // Task 3: Use AI to determine optimal delivery time
        long optimalTime = System.currentTimeMillis() + (8 * 3600 * 1000); // Simulate 8 AM next day
        predictiveDelivery.scheduleOptimalDelivery(userId, "SMS", "+1234567890", smsBody, optimalTime);
        predictiveDelivery.scheduleOptimalDelivery(userId, "EMAIL", "user@example.com", emailBody, optimalTime);
    }
    
    private Map<String, Object> buildTemplateData(TransactionCompletedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("userName", "Customer");
        data.put("transactionType", event.getTransactionType());
        data.put("status", event.getStatus());
        data.put("amount", event.getAmount());
        data.put("currency", event.getCurrency());
        data.put("transactionId", event.getTransactionId());
        data.put("timestamp", java.time.LocalDateTime.now().format(FORMATTER));
        return data;
    }
}
