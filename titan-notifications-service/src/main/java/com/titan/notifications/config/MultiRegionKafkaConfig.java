package com.titan.notifications.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration.
 * Supports both:
 *   - Local dev:       PLAINTEXT (no credentials needed)
 *   - Confluent Cloud: SASL_SSL  (set KAFKA_SECURITY_PROTOCOL=SASL_SSL on Render)
 */
@Configuration
public class MultiRegionKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // Optional second region (leave blank if not used)
    @Value("${spring.kafka.bootstrap-servers.region2:}")
    private String region2Servers;

    @Value("${spring.kafka.consumer.group-id:titan-notifications}")
    private String groupId;

    // PLAINTEXT (local) or SASL_SSL (Confluent Cloud)
    @Value("${spring.kafka.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    // Confluent Cloud API Key  → KAFKA_SASL_USERNAME env var
    @Value("${spring.kafka.properties.sasl.jaas.username:}")
    private String saslUsername;

    // Confluent Cloud API Secret → KAFKA_SASL_PASSWORD env var
    @Value("${spring.kafka.properties.sasl.jaas.password:}")
    private String saslPassword;

    @Bean
    public ConsumerFactory<String, String> multiRegionConsumerFactory() {
        Map<String, Object> props = new HashMap<>();

        // Bootstrap servers — combine primary + optional second region
        String allServers = bootstrapServers;
        if (region2Servers != null && !region2Servers.isBlank()) {
            allServers = bootstrapServers + "," + region2Servers;
        }
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, allServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");

        // Reliability settings
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Rack awareness for multi-region (no-op on Confluent Cloud)
        props.put(ConsumerConfig.CLIENT_RACK_CONFIG,
                System.getenv().getOrDefault("KAFKA_RACK_ID", "rack-1"));

        // ── Security ─────────────────────────────────────────────────────
        props.put("security.protocol", securityProtocol);

        if ("SASL_SSL".equalsIgnoreCase(securityProtocol)
                || "SASL_PLAINTEXT".equalsIgnoreCase(securityProtocol)) {

            props.put(SaslConfigs.SASL_MECHANISM, saslMechanism);

            // Build JAAS config from individual username/password env vars
            // (safer than embedding the full jaas string in properties)
            String jaasConfig = String.format(
                    "org.apache.kafka.common.security.plain.PlainLoginModule required "
                            + "username=\"%s\" password=\"%s\";",
                    saslUsername, saslPassword);
            props.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);
        }

        if ("SASL_SSL".equalsIgnoreCase(securityProtocol)
                || "SSL".equalsIgnoreCase(securityProtocol)) {
            // Confluent Cloud uses publicly trusted certs — no custom truststore needed
            props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
        }

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(multiRegionConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties()
                .setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
