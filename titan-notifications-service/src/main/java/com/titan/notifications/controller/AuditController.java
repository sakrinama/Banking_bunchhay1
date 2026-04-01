package com.titan.notifications.controller;

import com.titan.notifications.repository.NotificationAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {
    
    private final NotificationAuditRepository repository;
    
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<?> getByTransaction(@PathVariable String transactionId) {
        return ResponseEntity.ok(repository.findByTransactionId(transactionId));
    }
    
    @GetMapping("/account/{accountId}")
    public ResponseEntity<?> getByAccount(@PathVariable String accountId) {
        return ResponseEntity.ok(repository.findByAccountId(accountId));
    }
}
