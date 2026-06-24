package com.example.wallet.settlement;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 정산 결과는 한 번 만들어지면 다시 고치지 않는 스냅샷이다(LedgerEntry와 같은 불변 원칙).
// (merchantId, settlementDate) UNIQUE 제약이 "같은 가맹점·같은 날짜를 두 번 정산할 수 없다"를
// DB 레벨에서 보장한다 — 재실행 멱등성을 LedgerEntry.idempotencyKey와 같은 방식으로 푼다.
@Entity
@Table(name = "settlement", uniqueConstraints = @UniqueConstraint(columnNames = { "merchant_id", "settlement_date" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long merchantId;

	@Column(nullable = false)
	private LocalDate settlementDate;

	@Column(nullable = false)
	private Long totalPaymentAmount;

	@Column(nullable = false)
	private Long totalRefundAmount;

	@Column(nullable = false)
	private Long feeAmount;

	@Column(nullable = false)
	private Long settlementAmount;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Settlement(Long merchantId, LocalDate settlementDate, Long totalPaymentAmount, Long totalRefundAmount,
			Long feeAmount) {
		this.merchantId = merchantId;
		this.settlementDate = settlementDate;
		this.totalPaymentAmount = totalPaymentAmount;
		this.totalRefundAmount = totalRefundAmount;
		this.feeAmount = feeAmount;
		this.settlementAmount = totalPaymentAmount - totalRefundAmount - feeAmount;
	}

	public static Settlement of(Long merchantId, LocalDate settlementDate, long totalPaymentAmount,
			long totalRefundAmount, long feeAmount) {
		return new Settlement(merchantId, settlementDate, totalPaymentAmount, totalRefundAmount, feeAmount);
	}
}
