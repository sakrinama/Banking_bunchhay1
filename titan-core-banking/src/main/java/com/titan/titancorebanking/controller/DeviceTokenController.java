package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    record DeviceTokenRequest(String deviceToken, String platform) {}

    /**
     * POST /api/v1/notifications/device-token
     * iOS app calls this after login to register the APNs token.
     * Requires JWT bearer token in Authorization header.
     */
    @PostMapping("/device-token")
    public ResponseEntity<Void> registerDeviceToken(
            @RequestBody DeviceTokenRequest request,
            Authentication auth) {

        String username = auth.getName();
        deviceTokenService.registerToken(username, request.deviceToken(), request.platform());
        return ResponseEntity.ok().build();
    }
}
