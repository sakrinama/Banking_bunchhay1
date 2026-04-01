package com.titan.promotions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.service.PromotionEvaluationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TransactionEventConsumer {

    private final PromotionEvaluationService evaluationService;
    private final ObjectMapper objectMapper;
    private final Counter consumerLagCounter;

    public TransactionEventConsumer(PromotionEvaluationService evaluationService,
                                    ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry) {
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
        this.consumerLagCounter = Counter.builder("kafka.consumer.lag")
            .description("Kafka consumer lag")
            .register(meterRegistry);
    }

    @KafkaListener(
        topics = "${spring.kafka.consumer.topic:banking.transactions.completed}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            TransactionCompletedEvent event = objectMapper.readValue(record.value(), TransactionCompletedEvent.class);
            log.info("Processing transaction event: transactionId={}, amount={}",
                event.getTransactionId(), event.getAmount());
            evaluationService.evaluateTransaction(event);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process transaction event: key={}, error={}", record.key(), e.getMessage(), e);
            consumerLagCounter.increment();
        }
    }
}
