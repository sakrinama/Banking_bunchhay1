package com.titan.notifications.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Transaction Completed Event
 * Mirror of the event published by Core Banking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionCompletedEvent {
    private String eventId;
    private String eventType;
    private String eventVersion;
    private String timestamp;
    private String correlationId;
    
    // Transaction details
    private Long transactionId;
    private Long accountId;
    private BigDecimal amount;
    private String currency;
    private String transactionType;
    private String status;
    private String description;
    private Map<String, Object> metadata;
    
    // Task 7: i18n support
    private String locale;
}
