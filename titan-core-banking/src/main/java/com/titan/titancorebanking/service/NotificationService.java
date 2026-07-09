package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Calls titan-notifications-service via HTTP REST after every transaction.
 *
 * This is the NO-KAFKA transport: core-banking → HTTP POST → notifications service.
 * All calls are fire-and-forget (timeout 3 s) — a slow or down notification
 * service NEVER blocks or rolls back a transaction.
 *
 * Endpoint: POST {NOTIFICATION_SERVICE_URL}/api/notify/transaction
 */
@Service
@Slf4j
public class NotificationService {

    private final RestClient restClient;
    private final String notificationServiceUrl;

    public NotificationService(
            RestClient.Builder builder,
            @Value("${notification.service.url:https://banking-bunchhay1-2.onrender.com}") String notificationServiceUrl) {
        this.restClient = builder
                .requestInterceptor((req, body, execution) -> {
                    req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return execution.execute(req, body);
                })
                .build();
        this.notificationServiceUrl = notificationServiceUrl;
        log.info("📡 NotificationService → {}", notificationServiceUrl);
    }

    /**
     * Send notification for a completed transaction.
     * Called by TransactionService after transfer/deposit/withdraw.
     * Non-blocking: runs on a virtual thread, never throws to the caller.
     */
    public void notifyTransaction(Transaction tx) {
        Thread.ofVirtual().name("notif-", 0).start(() -> {
            try {
                Map<String, Object> payload = buildPayload(tx);
                restClient.post()
                        .uri(notificationServiceUrl + "/api/notify/transaction")
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                log.info("✅ Notification sent for txId={}", tx.getId());
            } catch (Exception e) {
                // Never propagate — notification failure must NOT affect the transaction
                log.warn("⚠️  Notification skipped for txId={}: {}", tx.getId(), e.getMessage());
            }
        });
    }

    /**
     * Send a simple notification by username and message text.
     * Used by TransactionEventListener (Spring application events).
     * Fire-and-forget — runs on a virtual thread.
     */
    public void sendNotification(String username, String message) {
        Thread.ofVirtual().name("notif-simple-", 0).start(() -> {
            try {
                Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("username", username);
                payload.put("message", message);
                payload.put("locale", "en");

                restClient.post()
                        .uri(notificationServiceUrl + "/api/notify/message")
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                log.info("✅ Notification sent for user={}", username);
            } catch (Exception e) {
                log.warn("⚠️  Notification skipped for user={}: {}", username, e.getMessage());
            }
        });
    }

    /**
     * Send notification for the RECEIVER of a transfer (Account B).
     * Called by TransactionService after a successful transfer only.
     * Non-blocking: runs on a virtual thread, never throws to the caller.
     */
    public void notifyTransactionReceiver(Transaction tx) {
        // Only relevant for TRANSFER transactions that have both accounts
        if (tx.getToAccount() == null || tx.getFromAccount() == null) return;

        Thread.ofVirtual().name("notif-recv-", 0).start(() -> {
            try {
                Map<String, Object> payload = buildReceiverPayload(tx);
                restClient.post()
                        .uri(notificationServiceUrl + "/api/notify/transaction")
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                log.info("✅ Receiver notification sent for txId={}", tx.getId());
            } catch (Exception e) {
                log.warn("⚠️  Receiver notification skipped for txId={}: {}", tx.getId(), e.getMessage());
            }
        });
    }

    // ── Build payload targeting Account B (receiver) ──────────────────────────
    private Map<String, Object> buildReceiverPayload(Transaction tx) {
        Account receiver = tx.getToAccount();

        String username  = receiver.getUser() != null ? receiver.getUser().getUsername() : "SYSTEM";
        String userEmail = receiver.getUser() != null ? receiver.getUser().getEmail()    : null;
        String currency  = receiver.getCurrency().name();

        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId",       String.valueOf(tx.getId()) + "-recv");
        payload.put("type",                "TRANSFER_RECEIVED");   // distinct type for receiver
        payload.put("status",              tx.getStatus().name());
        payload.put("amount",              tx.getAmount());
        payload.put("currency",            currency);
        payload.put("username",            username);              // ← Account B's username
        payload.put("note",                tx.getNote());
        payload.put("locale",              "en");
        payload.put("sourceAccountNumber", tx.getFromAccount().getAccountNumber());
        payload.put("targetAccountNumber", receiver.getAccountNumber());
        if (userEmail != null && !userEmail.isBlank()) {
            payload.put("userEmail", userEmail);
        }
        return payload;
    }

    // ── Build JSON payload matching TransactionNotificationRequest ────────────
    private Map<String, Object> buildPayload(Transaction tx) {
        Account primary = tx.getFromAccount() != null ? tx.getFromAccount() : tx.getToAccount();

        String username  = primary != null && primary.getUser() != null
                ? primary.getUser().getUsername() : "SYSTEM";
        String userEmail = primary != null && primary.getUser() != null
                ? primary.getUser().getEmail() : null;
        String currency  = primary != null ? primary.getCurrency().name() : "USD";

        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId",       String.valueOf(tx.getId()));
        payload.put("type",                tx.getTransactionType().name());
        payload.put("status",              tx.getStatus().name());
        payload.put("amount",              tx.getAmount());
        payload.put("currency",            currency);
        payload.put("username",            username);
        payload.put("note",                tx.getNote());
        payload.put("locale",              "en");

        if (tx.getFromAccount() != null) {
            payload.put("sourceAccountNumber", tx.getFromAccount().getAccountNumber());
        }
        if (tx.getToAccount() != null) {
            payload.put("targetAccountNumber", tx.getToAccount().getAccountNumber());
        }
        if (userEmail != null && !userEmail.isBlank()) {
            payload.put("userEmail", userEmail);
        }

        return payload;
    }
}