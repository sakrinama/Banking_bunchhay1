package com.titan.titancorebanking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Sends push notifications to iOS via Apple Push Notification service (APNs).
 *
 * Uses APNs HTTP/2 API with auth token (JWT) — no certificate file needed.
 * Requires:
 *   APNS_KEY_ID        — 10-char key ID from Apple Developer portal
 *   APNS_TEAM_ID       — 10-char Team ID from Apple Developer portal
 *   APNS_PRIVATE_KEY   — Contents of the .p8 file (AuthKey_XXXXXXXX.p8)
 *   APNS_BUNDLE_ID     — Your app bundle ID e.g. com.titan.banking
 *   APNS_SANDBOX       — true for development, false for production
 */
@Slf4j
@Service
public class ApnsPushService {

    @Value("${apns.key-id:}")
    private String keyId;

    @Value("${apns.team-id:}")
    private String teamId;

    @Value("${apns.private-key:}")
    private String privateKeyPem;

    @Value("${apns.bundle-id:com.titan.banking}")
    private String bundleId;

    @Value("${apns.sandbox:true}")
    private boolean sandbox;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Cached JWT token (valid for 1 hour per Apple spec)
    private String cachedJwt;
    private long jwtCreatedAt = 0;

    /**
     * Send a push notification to a single device token.
     *
     * @param deviceToken  hex APNs device token from iOS
     * @param title        notification title shown on lock screen
     * @param body         notification body text
     */
    public void sendPush(String deviceToken, String title, String body) {
        if (!isConfigured()) {
            log.warn("⚠️ APNs not configured — skipping push to device {}", deviceToken.substring(0, 8) + "...");
            return;
        }

        try {
            String host = sandbox
                    ? "https://api.sandbox.push.apple.com"
                    : "https://api.push.apple.com";

            String url = host + "/3/device/" + deviceToken;

            // Build APNs payload
            Map<String, Object> alert  = Map.of("title", title, "body", body);
            Map<String, Object> aps    = Map.of("alert", alert, "sound", "default", "badge", 1);
            Map<String, Object> payload = Map.of("aps", aps);
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("authorization",  "bearer " + getJwt())
                    .header("apns-topic",      bundleId)
                    .header("apns-push-type",  "alert")
                    .header("apns-priority",   "10")
                    .header("content-type",    "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("✅ APNs push sent to device {}...", deviceToken.substring(0, 8));
            } else {
                log.error("❌ APNs error {}: {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("❌ APNs push failed: {}", e.getMessage());
        }
    }

    /**
     * Build or return cached APNs JWT (valid 1 hour).
     * Apple requires ES256 JWT signed with the .p8 private key.
     */
    private String getJwt() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        // Refresh if older than 55 minutes
        if (cachedJwt != null && (now - jwtCreatedAt) < 3300) {
            return cachedJwt;
        }

        // Build JWT header + payload
        String header  = base64url("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
        String payload = base64url("{\"iss\":\"" + teamId + "\",\"iat\":" + now + "}");
        String unsigned = header + "." + payload;

        // Sign with ES256 using the .p8 private key
        String cleanedKey = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = java.util.Base64.getDecoder().decode(cleanedKey);
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC");
        java.security.PrivateKey pk = kf.generatePrivate(spec);

        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
        sig.initSign(pk);
        sig.update(unsigned.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] signature = sig.sign();

        cachedJwt = unsigned + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        jwtCreatedAt = now;
        return cachedJwt;
    }

    private String base64url(String input) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean isConfigured() {
        return keyId != null && !keyId.isBlank()
                && teamId != null && !teamId.isBlank()
                && privateKeyPem != null && !privateKeyPem.isBlank();
    }
}
