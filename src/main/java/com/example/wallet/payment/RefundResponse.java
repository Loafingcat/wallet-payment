package com.example.wallet.payment;

public record RefundResponse(Long paymentId, Long refundAmount, Long totalRefunded, Long balance) {
}
