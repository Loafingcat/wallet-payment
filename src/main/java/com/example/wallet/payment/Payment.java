package com.example.wallet.payment;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// LedgerEntry(불변)와 다르게 이 엔티티는 의도적으로 가변이다. Payment는 "PG 승인이 끼는
// 결제를 지금까지 어디까지 진행했는지" 추적하는 오케스트레이션 기록이지, 돈이 실제로
// 움직였다는 확정된 사실 그 자체가 아니다. 절대 규칙 2번(원장 불변)은 LedgerEntry에
// 적용되는 것이고 여기엔 적용되지 않는다 — status가 APPROVED로 바뀌면서 ledgerEntryId가
// 채워지는 순간, 그 시점부터 진짜 "확정된 사실"인 LedgerEntry가 따로 생긴다.
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long walletId;

	@Column(nullable = false)
	private Long merchantId;

	@Column(nullable = false)
	private Long amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentStatus status;

	// 우리 API의 멱등키이자, PG에 보내는 멱등키이기도 하다(같은 문자열을 그대로 재사용 —
	// 둘 다 unique해야 하는 건 같으니 컬럼을 따로 둘 이유가 없었다).
	@Column(nullable = false, unique = true)
	private String idempotencyKey;

	@Column
	private Long ledgerEntryId;

	@Column
	private String failureReason;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	public Payment(Long walletId, Long merchantId, long amount, String idempotencyKey) {
		this.walletId = walletId;
		this.merchantId = merchantId;
		this.amount = amount;
		this.idempotencyKey = idempotencyKey;
		this.status = PaymentStatus.PENDING_PG;
	}

	public void approve(Long ledgerEntryId) {
		this.status = PaymentStatus.APPROVED;
		this.ledgerEntryId = ledgerEntryId;
	}

	public void fail(String reason) {
		this.status = PaymentStatus.FAILED;
		this.failureReason = reason;
	}

	public boolean isPending() {
		return this.status == PaymentStatus.PENDING_PG;
	}
}
