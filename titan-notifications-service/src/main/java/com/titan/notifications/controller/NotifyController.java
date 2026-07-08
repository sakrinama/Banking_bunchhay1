package com.titan.notifications.controller;

import com.titan.notifications.dto.TransactionNotificationRequest;
import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * HTTP entry point for direct REST calls from titan-core-banking.
 *
 * When Kafka is NOT available (e.g., Render free tier without Confluent Cloud),
 * core-banking calls POST /api/notify/transaction after every transaction.
 *
 * This controller converts the HTTP payload into the same TransactionCompletedEvent
 * that the Kafka consumer uses — so NotificationService is called identically
 * regardless of the transport (Kafka or HTTP).
 */
@RestController
@RequestMapping("/api/notify")
@Slf4j
@RequiredArgsConstructor
public class NotifyController {

    private final NotificationService notificationService;

    /**
     * POST /api/notify/transaction
     *
     * Called by titan-core-banking after every successful transaction.
     * Core-banking fires this as fire-and-forget (3 s timeout) so it never
     * blocks the transaction itself even if the notification service is slow.
     *
     * Request body: TransactionNotificationRequest JSON
     * Response:     200 OK immediately (processing happens async)
     */
    @PostMapping("/transaction")
    public ResponseEntity<Map<String, String>> notifyTransaction(
            @RequestBody TransactionNotificationRequest req) {

        log.info("📨 HTTP notify received: txId={}, type={}, amount={}, user={}",
                req.getTransactionId(), req.getType(), req.getAmount(), req.getUsername());

        // Convert HTTP payload → TransactionCompletedEvent (same model Kafka uses)
        TransactionCompletedEvent event = toEvent(req);

        // Process async so we respond instantly and don't block core-banking
        Thread.ofVirtual().name("notify-", 0).start(() -> {
            try {
                notificationService.sendNotifications(event);
            } catch (Exception e) {
                log.error("❌ Notification processing error: {}", e.getMessage(), e);
            }
        });

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "transactionId", req.getTransactionId() != null ? req.getTransactionId() : "unknown"
        ));
    }

    /**
     * GET /api/notify/health
     * Simple ping so core-banking can verify connectivity on startup.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "titan-notifications"));
    }

    // ── Convert HTTP DTO → internal event model ───────────────────────────────
    private TransactionCompletedEvent toEvent(TransactionNotificationRequest req) {
        return TransactionCompletedEvent.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .eventType("TransactionCompleted")
                .eventVersion("1.0")
                .transactionId(req.getTransactionId())
                .timestamp(java.time.Instant.now())
                .amount(req.getAmount())
                .currency(req.getCurrency())
                .type(req.getType())
                .status(req.getStatus())
                .sourceAccountNumber(req.getSourceAccountNumber())
                .targetAccountNumber(req.getTargetAccountNumber())
                .username(req.getUsername())
                .note(req.getNote())
                .locale(req.getLocale())
                // Inject userEmail into metadata so NotificationService.resolveEmail() picks it up
                .metadata(buildMetadata(req))
                .build();
    }

    private java.util.Map<String, String> buildMetadata(TransactionNotificationRequest req) {
        java.util.Map<String, String> meta = new java.util.HashMap<>();
        meta.put("transport", "http");
        meta.put("source", "titan-core-banking");
        if (req.getUserEmail() != null && !req.getUserEmail().isBlank()) {
            meta.put("userEmail", req.getUserEmail());
        }
        if (req.getMetadata() != null) {
            meta.putAll(req.getMetadata());
        }
        return meta;
    }
}
