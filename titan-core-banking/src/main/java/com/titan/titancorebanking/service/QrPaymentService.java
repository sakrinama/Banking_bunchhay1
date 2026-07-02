package com.titan.titancorebanking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.titan.titancorebanking.dto.request.GenerateQrRequest;
import com.titan.titancorebanking.dto.request.PayByQrRequest;
import com.titan.titancorebanking.dto.response.QrPaymentResponse;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.QrPayment;
import com.titan.titancorebanking.model.QrPayment.QrStatus;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.QrPaymentRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ✅ TITAN QR Payment Service
 *
 * Two operations:
 *  1. generateQr()  – creates a QrPayment record, renders ZXing QR PNG, returns base64 image
 *  2. payByQr()     – validates token, deducts payer, credits payee, records Transaction
 *
 * Scheduled task: expireStaleQrCodes() runs every minute to flip PENDING → EXPIRED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QrPaymentService {

    // ── Constants ────────────────────────────────────────────────────────────────
    private static final int QR_IMAGE_SIZE    = 300;   // pixels
    private static final int DEFAULT_TTL_MIN  = 15;    // minutes

    // ── Dependencies ─────────────────────────────────────────────────────────────
    private final AccountRepository     accountRepository;
    private final QrPaymentRepository   qrPaymentRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder       passwordEncoder;

    // =============================================================================
    // 1. GENERATE QR
    // =============================================================================

    /**
     * Generates a QR code for receiving a payment.
     * Called by the payee (account owner who wants to receive money).
     *
     * @param request  GenerateQrRequest (payeeAccountNumber, optional amount, note, ttlMinutes)
     * @param username authenticated user's username (for ownership check)
     * @return QrPaymentResponse including base64-encoded PNG of the QR image
     */
    @Transactional
    public QrPaymentResponse generateQr(GenerateQrRequest request, String username) {
        // 1️⃣  Resolve payee account and verify ownership
        Account payee = accountRepository
                .findByAccountNumber(request.payeeAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + request.payeeAccountNumber()));

        if (!payee.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only generate QR codes for your own accounts.");
        }

        // 2️⃣  Build a unique QR token (collision-safe)
        String qrToken = generateUniqueToken();

        // 3️⃣  Determine expiry
        int ttl = (request.ttlMinutes() != null) ? request.ttlMinutes() : DEFAULT_TTL_MIN;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttl);

        // 4️⃣  Persist the QR record
        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payee)
                .amount(request.amount())
                .currency(payee.getCurrency().name())
                .note(request.note())
                .status(QrStatus.PENDING)
                .expiresAt(expiresAt)
                .build();

        qrPaymentRepository.save(qrPayment);
        log.info("✅ QR generated: token={} payee={} amount={}", qrToken,
                request.payeeAccountNumber(), request.amount());

        // 5️⃣  Render QR image and return response
        String base64Image = renderQrToBase64(qrToken);
        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 2. PAY BY QR
    // =============================================================================

    /**
     * Processes a payment initiated by scanning a QR code.
     * Called by the payer.
     *
     * @param request  PayByQrRequest (qrCode token, payerAccountNumber, amount, pin)
     * @param username authenticated user's username
     * @return QrPaymentResponse with status COMPLETED and settled transaction ID
     */
    @Transactional
    public QrPaymentResponse payByQr(PayByQrRequest request, String username) {
        // 1️⃣  Look up the QR record
        QrPayment qrPayment = qrPaymentRepository.findByQrCode(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        // 2️⃣  Check QR status and expiry
        if (qrPayment.getStatus() == QrStatus.EXPIRED ||
                LocalDateTime.now().isAfter(qrPayment.getExpiresAt())) {
            qrPayment.setStatus(QrStatus.EXPIRED);
            qrPaymentRepository.save(qrPayment);
            throw new IllegalStateException("QR code has expired.");
        }
        if (qrPayment.getStatus() == QrStatus.COMPLETED) {
            throw new IllegalStateException("QR code has already been used.");
        }
        if (qrPayment.getStatus() == QrStatus.CANCELLED) {
            throw new IllegalStateException("QR code has been cancelled.");
        }

        // 3️⃣  Resolve payer account and verify ownership
        Account payer = accountRepository
                .findByAccountNumber(request.payerAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payer account not found: " + request.payerAccountNumber()));

        if (!payer.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only pay from your own accounts.");
        }

        // 4️⃣  Validate PIN
        if (!passwordEncoder.matches(request.pin(), payer.getUser().getPin())) {
            throw new SecurityException("Invalid PIN.");
        }

        // 5️⃣  Determine payment amount
        BigDecimal paymentAmount = resolveAmount(qrPayment, request);

        // 6️⃣  Guard: payer cannot pay themselves
        Account payee = qrPayment.getPayeeAccount();
        if (payer.getAccountNumber().equals(payee.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot pay yourself via QR.");
        }

        // 7️⃣  Balance check
        if (payer.getBalance().compareTo(paymentAmount) < 0) {
            throw new IllegalStateException("Insufficient balance.");
        }

        // 8️⃣  Move funds (lock in consistent order to prevent deadlock)
        boolean lockPayerFirst = payer.getAccountNumber()
                .compareTo(payee.getAccountNumber()) < 0;

        Account first  = lockPayerFirst ? payer : payee;
        Account second = lockPayerFirst ? payee : payer;
        accountRepository.findById(first.getId());   // pessimistic lock via @Lock in findByAccountNumber
        accountRepository.findById(second.getId());

        payer.setBalance(payer.getBalance().subtract(paymentAmount));
        payee.setBalance(payee.getBalance().add(paymentAmount));
        accountRepository.save(payer);
        accountRepository.save(payee);

        // 9️⃣  Record Transaction
        Transaction tx = Transaction.builder()
                .fromAccount(payer)
                .toAccount(payee)
                .amount(paymentAmount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note("QR Payment – " + (qrPayment.getNote() != null ? qrPayment.getNote() : ""))
                .timestamp(LocalDateTime.now())
                .transactionReference("QR-" + qrPayment.getQrCode().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        // 🔟  Update QR record
        qrPayment.setStatus(QrStatus.COMPLETED);
        qrPayment.setPayerAccount(payer);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPaymentRepository.save(qrPayment);

        log.info("✅ QR payment completed: qrCode={} payer={} payee={} amount={}",
                request.qrCode(), payer.getAccountNumber(),
                payee.getAccountNumber(), paymentAmount);

        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 3. CANCEL QR
    // =============================================================================

    /**
     * Allows the payee to cancel a pending QR before it is used.
     */
    @Transactional
    public QrPaymentResponse cancelQr(String qrCode, String username) {
        QrPayment qrPayment = qrPaymentRepository.findByQrCode(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("QR code not found."));

        if (!qrPayment.getPayeeAccount().getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only cancel your own QR codes.");
        }
        if (qrPayment.getStatus() != QrStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING QR codes can be cancelled. Current status: " + qrPayment.getStatus());
        }

        qrPayment.setStatus(QrStatus.CANCELLED);
        qrPaymentRepository.save(qrPayment);
        log.info("🚫 QR cancelled: token={}", qrCode);
        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 4. GET QR HISTORY
    // =============================================================================

    public List<QrPaymentResponse> getQrHistory(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));

        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Access denied.");
        }

        return qrPaymentRepository
                .findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(q -> toResponse(q, null))
                .toList();
    }

    // =============================================================================
    // 5. SCHEDULED: EXPIRE STALE QRs
    // =============================================================================

    @Scheduled(fixedDelay = 60_000)   // every 60 seconds
    @Transactional
    public void expireStaleQrCodes() {
        int expired = qrPaymentRepository.expireStaleQrCodes(LocalDateTime.now());
        if (expired > 0) {
            log.info("⏰ Expired {} stale QR codes.", expired);
        }
    }

    // =============================================================================
    // PRIVATE HELPERS
    // =============================================================================

    /**
     * Generates a collision-safe UUID-based token, retrying if a duplicate exists.
     */
    private String generateUniqueToken() {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (qrPaymentRepository.existsByQrCode(token));
        return token;
    }

    /**
     * Renders the QR token string into a 300×300 PNG and returns it as a Base64 string.
     */
    private String renderQrToBase64(String content) {
        try {
            var hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN,           2
            );
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE,
                    QR_IMAGE_SIZE, QR_IMAGE_SIZE, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());

        } catch (Exception e) {
            log.error("❌ Failed to render QR image for content={}", content, e);
            throw new RuntimeException("QR image generation failed.", e);
        }
    }

    /**
     * Resolves the actual payment amount:
     *  - Uses QR's fixed amount if set
     *  - Falls back to the amount provided in the PayByQrRequest (open-amount QR)
     */
    private BigDecimal resolveAmount(QrPayment qrPayment, PayByQrRequest request) {
        if (qrPayment.getAmount() != null) {
            return qrPayment.getAmount();   // fixed-amount QR
        }
        if (request.amount() == null) {
            throw new IllegalArgumentException(
                    "This QR code requires you to specify an amount.");
        }
        return request.amount();
    }

    /**
     * Converts a QrPayment entity to the public response DTO.
     */
    private QrPaymentResponse toResponse(QrPayment q, String base64Image) {
        return new QrPaymentResponse(
                q.getId(),
                q.getQrCode(),
                base64Image,
                q.getStatus().name(),
                q.getAmount(),
                q.getCurrency(),
                q.getPayeeAccount().getAccountNumber(),
                q.getPayerAccount() != null ? q.getPayerAccount().getAccountNumber() : null,
                q.getNote(),
                q.getCreatedAt(),
                q.getExpiresAt(),
                q.getPaidAt(),
                q.getTransaction() != null ? q.getTransaction().getId() : null
        );
    }
}
