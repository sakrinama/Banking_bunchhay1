package com.titan.promotions.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterQueueHandler {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    @KafkaListener(topics = "transactions-dlq", groupId = "dlq-recovery")
    public void handleDeadLetter(String message, 
                                  @Header(KafkaHeaders.EXCEPTION_MESSAGE) String error,
                                  @Header(value = "retry-count", required = false) Integer retryCount) {
        
        int currentRetry = retryCount != null ? retryCount : 0;
        
        if (currentRetry >= 3) {
            log.error("DLQ message exceeded max retries, moving to poison queue: {}", message);
            kafkaTemplate.send("transactions-poison", message);
            return;
        }
        
        log.warn("Retrying DLQ message (attempt {}): {}", currentRetry + 1, message);
        
        // Exponential backoff
        try {
            Thread.sleep((long) Math.pow(2, currentRetry) * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Retry to main topic with incremented counter
        kafkaTemplate.send("transactions", message);
    }
}
