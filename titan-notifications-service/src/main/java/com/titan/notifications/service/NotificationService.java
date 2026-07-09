package com.titan.notifications.service;

import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.model.UserPreference;
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
        
        // ── Resolve userId — use username from event, fallback to sourceAccountNumber
        String userId = resolveUserId(event);
        String locale = event.getLocale() != null ? event.getLocale() : preferenceService.getPreferredLocale(userId);
        boolean isHighValue = event.getAmount() != null && event.getAmount().compareTo(HIGH_VALUE_THRESHOLD) > 0;
        boolean isMarketing = "PROMOTION".equals(event.getTransactionType());
        boolean isReceiverSide = "TRANSFER_RECEIVED".equals(event.getTransactionType());

        if (isMarketing && !preferenceService.canSendMarketing(userId)) {
            log.info("🚫 User {} opted out of marketing, dropping event silently", userId);
            return;
        }
        
        if (!isMarketing && !preferenceService.canSendTransactionAlert(userId)) {
            log.info("🚫 User {} disabled transaction alerts", userId);
            return;
        }

        // ── Resolve real contact details from UserPreference ──────────────────
        UserPreference pref = preferenceService.getPreferences(userId);
        String recipientEmail = resolveEmail(pref, event);
        String recipientPhone = resolvePhone(pref, event);
        
        log.info("📬 Resolved contact — userId={}, email={}, phone={}", userId, mask(recipientEmail), mask(recipientPhone));
        
        // ── Task 1: Instant WebSocket push for real-time alerts ───────────────
        Map<String, Object> wsPayload = buildTemplateData(event);
        webSocketService.pushToUser(userId, "TRANSACTION_ALERT", wsPayload);

        // ── TRANSFER_RECEIVED: always write to audit immediately so iOS can poll it ──
        // Do NOT defer receiver-side events to predictiveDelivery — Account B must
        // see the notification the moment the next poll runs (every 15 s).
        if (isReceiverSide) {
            String receiverMessage = buildReceiverMessage(event);
            auditService.logAttempt(
                    String.valueOf(event.getTransactionId()), userId,
                    "IN_APP", userId, receiverMessage,
                    "internal", locale, true);
            log.info("✅ TRANSFER_RECEIVED audit written immediately for userId={}", userId);
            log.info("=".repeat(80));
            log.info("✅ Notification processing completed for transaction: {}", event.getTransactionId());
            log.info("=".repeat(80));
            return;
        }

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
            processLowPriority(event, userId, locale, recipientEmail, recipientPhone);
        } else {
            processUrgent(event, userId, locale, isHighValue, recipientEmail, recipientPhone);
        }
        
        log.info("=".repeat(80));
        log.info("✅ Notification processing completed for transaction: {}", event.getTransactionId());
        log.info("=".repeat(80));
    }
    
    // ── Helper: resolve userId from event ─────────────────────────────────────
    private String resolveUserId(TransactionCompletedEvent event) {
        if (event.getUsername() != null && !event.getUsername().isBlank()
                && !"SYSTEM".equalsIgnoreCase(event.getUsername())) {
            return event.getUsername();
        }
        if (event.getSourceAccountNumber() != null && !event.getSourceAccountNumber().isBlank()) {
            return event.getSourceAccountNumber();
        }
        return "UNKNOWN";
    }

    // ── Helper: pick email from preference, fallback to event metadata ─────────
    private String resolveEmail(UserPreference pref, TransactionCompletedEvent event) {
        // 1. Stored preference (most reliable — user registered this email)
        if (pref.getEmail() != null && !pref.getEmail().isBlank()
                && !"user@example.com".equals(pref.getEmail())) {
            return pref.getEmail();
        }
        // 2. Event metadata (core-banking can inject email in metadata map)
        if (event.getMetadata() != null) {
            String metaEmail = event.getMetadata().get("userEmail");
            if (metaEmail != null && !metaEmail.isBlank()) return metaEmail;
        }
        // 3. Last resort — log a warning but don't crash
        log.warn("⚠️ No real email found for userId={}. Notification will be skipped for EMAIL channel.", resolveUserId(event));
        return null;
    }

    // ── Helper: pick phone from preference, fallback to event metadata ─────────
    private String resolvePhone(UserPreference pref, TransactionCompletedEvent event) {
        // 1. Stored preference
        if (pref.getSmsNumber() != null && !pref.getSmsNumber().isBlank()
                && !"+1234567890".equals(pref.getSmsNumber())) {
            return pref.getSmsNumber();
        }
        // 2. Event metadata
        if (event.getMetadata() != null) {
            String metaPhone = event.getMetadata().get("userPhone");
            if (metaPhone != null && !metaPhone.isBlank()) return metaPhone;
        }
        log.warn("⚠️ No real phone found for userId={}. Notification will be skipped for SMS channel.", resolveUserId(event));
        return null;
    }

    // ── Mask PII in logs (show only first 3 chars) ────────────────────────────
    private String mask(String value) {
        if (value == null) return "null";
        if (value.length() <= 3) return "***";
        return value.substring(0, 3) + "***";
    }
    
    private void processUrgent(TransactionCompletedEvent event, String userId, String locale,
                               boolean isHighValue, String recipientEmail, String recipientPhone) {
        Map<String, Object> data = buildTemplateData(event);
        String templateName = isHighValue ? "high_value_alert" : "transaction_alert";
        
        try {
            // ── SMS (only if we have a real phone number) ─────────────────────
            if (recipientPhone != null) {
                String smsBody = templateService.renderSms(templateName, data, locale);
                providerService.sendSms(recipientPhone, smsBody);
                auditService.logAttempt(String.valueOf(event.getTransactionId()), userId, "SMS",
                        recipientPhone, smsBody, "twilio", locale, true);
            }

            // ── Email (only if we have a real email address) ──────────────────
            if (recipientEmail != null) {
                String emailBody = templateService.renderEmail(templateName, data, locale);
                providerService.sendEmail(recipientEmail, emailBody);
                auditService.logAttempt(String.valueOf(event.getTransactionId()), userId, "EMAIL",
                        recipientEmail, emailBody, "sendgrid", locale, true);
            }
        } catch (Exception e) {
            log.error("❌ Notification failed: {}", e.getMessage());
            if (recipientPhone != null) {
                auditService.logFailure(String.valueOf(event.getTransactionId()), userId, "SMS",
                        recipientPhone, e.getMessage(), locale);
            }
            if (recipientEmail != null) {
                auditService.logFailure(String.valueOf(event.getTransactionId()), userId, "EMAIL",
                        recipientEmail, e.getMessage(), locale);
            }
        }
    }
    
    private void processLowPriority(TransactionCompletedEvent event, String userId, String locale,
                                    String recipientEmail, String recipientPhone) {
        Map<String, Object> data = buildTemplateData(event);

        // Always write to audit immediately so iOS polling can detect it right away.
        // Then also schedule the email/SMS for optimal delivery time.
        String inAppMessage = (String) data.getOrDefault("messagePrefix",
                "Transaction " + event.getTransactionId() + " processed.");
        auditService.logAttempt(
                String.valueOf(event.getTransactionId()), userId,
                "IN_APP", userId, inAppMessage,
                "internal", locale, false);

        // Task 3: Use AI to determine optimal delivery time (8 AM next day)
        long optimalTime = System.currentTimeMillis() + (8 * 3600 * 1000L);

        if (recipientPhone != null) {
            String smsBody = templateService.renderSms("transaction_alert", data, locale);
            predictiveDelivery.scheduleOptimalDelivery(userId, "SMS", recipientPhone, smsBody, optimalTime);
        }
        if (recipientEmail != null) {
            String emailBody = templateService.renderEmail("transaction_alert", data, locale);
            predictiveDelivery.scheduleOptimalDelivery(userId, "EMAIL", recipientEmail, emailBody, optimalTime);
        }
    }

    // ── Build a human-readable message for Account B (receiver) ──────────────
    private String buildReceiverMessage(TransactionCompletedEvent event) {
        String amount   = event.getAmount()   != null ? event.getAmount().toPlainString() : "0";
        String currency = event.getCurrency() != null ? event.getCurrency() : "USD";
        String from     = event.getSourceAccountNumber() != null ? event.getSourceAccountNumber() : "another account";
        return String.format("You received %s %s from account %s", currency, amount, from);
    }
    
    private Map<String, Object> buildTemplateData(TransactionCompletedEvent event) {
        Map<String, Object> data = new HashMap<>();
        // Use real username from event instead of hardcoded "Customer"
        data.put("userName", event.getUsername() != null ? event.getUsername() : "Customer");
        data.put("transactionType", event.getTransactionType());
        data.put("status", event.getStatus());
        data.put("amount", event.getAmount());
        data.put("currency", event.getCurrency());
        data.put("transactionId", event.getTransactionId());
        data.put("sourceAccountNumber", event.getSourceAccountNumber());
        data.put("targetAccountNumber", event.getTargetAccountNumber());
        data.put("timestamp", java.time.LocalDateTime.now().format(FORMATTER));

        // Receiver-side leg: produce a "You received" message instead of "You sent"
        boolean isReceiverSide = "TRANSFER_RECEIVED".equals(event.getTransactionType());
        if (isReceiverSide) {
            data.put("messagePrefix", String.format("You received %s %s from account %s",
                    event.getCurrency() != null ? event.getCurrency() : "",
                    event.getAmount() != null ? event.getAmount().toPlainString() : "",
                    event.getSourceAccountNumber() != null ? event.getSourceAccountNumber() : ""));
        } else {
            data.put("messagePrefix", String.format("Your %s of %s %s was %s",
                    event.getTransactionType() != null ? event.getTransactionType().toLowerCase() : "transaction",
                    event.getCurrency() != null ? event.getCurrency() : "",
                    event.getAmount() != null ? event.getAmount().toPlainString() : "",
                    event.getStatus() != null ? event.getStatus().toLowerCase() : "processed"));
        }

        return data;
    }
}
