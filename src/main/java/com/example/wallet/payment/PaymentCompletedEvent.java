package com.example.wallet.payment;

// 결제 트랜잭션이 commit된 "후"에만 실제로 RabbitMQ로 나간다(PaymentEventPublisher 참고).
public record PaymentCompletedEvent(Long paymentId, Long walletId, Long merchantId, Long amount, Long balance) {
}
