package com.example.wallet.payment;

public record PaymentResponse(Long walletId, Long merchantId, Long balance) {
}
