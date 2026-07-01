package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.DeviceToken;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.DeviceTokenRepository;
import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final ApnsPushService apnsPushService;

    /**
     * Save (or update) an APNs device token for the logged-in user.
     * Called by the iOS app after login.
     */
    @Transactional
    public void registerToken(String username, String token, String platform) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        // Upsert — avoid duplicates
        if (deviceTokenRepository.existsByUserIdAndDeviceToken(user.getId(), token)) {
            log.info("📲 Device token already registered for user {}", username);
            return;
        }

        DeviceToken dt = DeviceToken.builder()
                .user(user)
                .deviceToken(token)
                .platform(platform != null ? platform : "IOS")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        deviceTokenRepository.save(dt);
        log.info("✅ Device token registered for user {} ({}...)", username, token.substring(0, 8));
    }

    /**
     * Send a push notification to ALL devices registered for a user.
     */
    public void pushToUser(Long userId, String title, String body) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.info("ℹ️ No device tokens for userId={} — skipping push", userId);
            return;
        }
        for (DeviceToken dt : tokens) {
            apnsPushService.sendPush(dt.getDeviceToken(), title, body);
        }
    }
}
