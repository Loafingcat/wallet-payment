package com.example.wallet.payment;

// paymentId는 이 결제를 기록한 LedgerEntry의 id다. 환불은 이 id로 원결제를 참조한다.
public record PaymentResponse(Long paymentId, Long walletId, Long merchantId, Long balance) {
}
