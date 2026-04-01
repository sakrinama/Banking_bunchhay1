package com.titan.event.consumer.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Base Kafka Consumer Configuration
 * Provides common configuration for all event consumers
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {
    
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;
    
    // 🔐 SSL Properties Injection (Phase 5: Security Hardening)
    @Value("${spring.kafka.ssl.trust-store-location:}")
    private String trustStoreLocation;

    @Value("${spring.kafka.ssl.trust-store-password:}")
    private String trustStorePassword;

    @Value("${spring.kafka.ssl.key-store-location:}")
    private String keyStoreLocation;

    @Value("${spring.kafka.ssl.key-store-password:}")
    private String keyStorePassword;
    
    @Value("${spring.kafka.security.protocol:PLAINTEXT}")
    private String securityProtocol;
    
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        
        // 🔐 SSL Configuration (Phase 5: Security Hardening)
        if ("SSL".equals(securityProtocol) && !trustStoreLocation.isEmpty()) {
            config.put("security.protocol", "SSL");
            config.put("ssl.truststore.location", trustStoreLocation.replace("file:", ""));
            config.put("ssl.truststore.password", trustStorePassword);
            config.put("ssl.keystore.location", keyStoreLocation.replace("file:", ""));
            config.put("ssl.keystore.password", keyStorePassword);
            config.put("ssl.endpoint.identification.algorithm", "");
        }
        
        // Manual offset management for reliability
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Performance tuning
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);  // 5 minutes
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);     // 30 seconds
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);  // 10 seconds
        
        return new DefaultKafkaConsumerFactory<>(config);
    }
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);  // 3 consumer threads for parallel processing
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
