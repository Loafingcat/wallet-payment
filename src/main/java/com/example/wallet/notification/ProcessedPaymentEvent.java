package com.example.wallet.notification;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 메시지 중복 수신 대비. RabbitMQ는 적어도 한 번(at-least-once) 전달을 보장할 뿐이라,
// 같은 결제완료 메시지가 두 번 와도 알림이 두 번 가면 안 된다. paymentId를 PK로 써서,
// "이미 처리한 paymentId면 INSERT가 막힌다"는 DB 제약으로 중복 처리를 막는다 — S3의
// idempotencyKey, S5의 (merchantId, settlementDate)와 같은 패턴이다.
@Entity
@Table(name = "processed_payment_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedPaymentEvent {

	@Id
	private Long paymentId;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime processedAt;

	public ProcessedPaymentEvent(Long paymentId) {
		this.paymentId = paymentId;
	}
}
