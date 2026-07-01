package com.titan.titancorebanking.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.titan.riskengine.RiskCheckRequest;
import com.titan.riskengine.RiskCheckResponse;
import com.titan.riskengine.RiskEngineServiceGrpc;

@Slf4j
@Service
public class RiskEngineGrpcService {

    private final RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub;

    // Hard limit enforced locally even when AI service is unreachable.
    // Matches RISK_HIGH_MAX_AMOUNT in titan-ai-service (default $100,000).
    @Value("${titan.ai.local.block.limit:100000}")
    private double localBlockLimit;

    public RiskEngineGrpcService(RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub) {
        this.riskStub = riskStub;
    }

    @CircuitBreaker(name = "risk-engine", fallbackMethod = "fallbackRiskCheck")
    public RiskCheckResponse analyzeTransaction(String userId, double amount) {
        log.info("📡 Calling Python AI for User: {}", userId);

        RiskCheckRequest request = RiskCheckRequest.newBuilder()
                .setUserId(userId)
                .setAmount(amount)
                .build();

        return riskStub.checkRisk(request);
    }

    // Fallback when AI service is unreachable.
    // Still enforces the hard block limit locally so high transfers
    // can never slip through just because AI is down.
    public RiskCheckResponse fallbackRiskCheck(String userId, double amount, Throwable t) {
        log.error("⚠️ AI Service is DOWN! Reason: {}.", t.getMessage());

        if (amount >= localBlockLimit) {
            log.warn("🚫 LOCAL BLOCK (AI down) | User: {} | Amount: ${} >= limit ${}",
                    userId, amount, localBlockLimit);
            return RiskCheckResponse.newBuilder()
                    .setRiskScore(100)
                    .setRiskLevel("BLOCKED")
                    .setAction("BLOCK")
                    .build();
        }

        // Below limit — allow with manual review flag
        log.warn("⚠️ Fail-open for amount ${} (below local limit ${}). Action: MANUAL_REVIEW", amount, localBlockLimit);
        return RiskCheckResponse.newBuilder()
                .setRiskScore(0)
                .setRiskLevel("UNKNOWN")
                .setAction("MANUAL_REVIEW")
                .build();
    }
}
