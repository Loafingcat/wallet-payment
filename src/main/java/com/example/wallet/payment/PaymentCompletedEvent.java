package com.example.wallet.payment;

// LedgerEntry와 같은 트랜잭션 안에서 OutboxEvent로 직렬화되어 적재되고(S10 아웃박스
// 패턴), OutboxRelay가 별도로 RabbitMQ에 발행한다. docs/outbox-pattern.md 참고.
public record PaymentCompletedEvent(Long paymentId, Long walletId, Long merchantId, Long amount, Long balance) {
}
