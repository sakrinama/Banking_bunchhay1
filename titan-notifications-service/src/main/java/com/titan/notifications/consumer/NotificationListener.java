package com.titan.notifications.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${spring.kafka.consumer.topic:banking.transactions.completed}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            TransactionCompletedEvent event = objectMapper.readValue(record.value(), TransactionCompletedEvent.class);
            log.info("📧 Processing notification for transaction: transactionId={}, amount={}, accountId={}",
                event.getTransactionId(), event.getAmount(), event.getAccountId());
            notificationService.sendNotifications(event);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process notification event: key={}, error={}", record.key(), e.getMessage(), e);
        }
    }
}
