package com.example.wallet.reconciliation;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 정합성 점검 배치(BalanceReconciliationService)가 캐시 잔액(Wallet.balance)과 원장
// 합계가 어긋난 지갑을 발견할 때마다 한 건씩 남긴다. 이 테이블 자체는 "탐지 기록"일
// 뿐이고, 잔액을 자동으로 고치는 로직은 없다 — 이유는 docs/balance-reconciliation.md.
@Entity
@Table(name = "balance_discrepancy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BalanceDiscrepancy {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long walletId;

	@Column(nullable = false)
	private Long cachedBalance;

	@Column(nullable = false)
	private Long ledgerSum;

	@Column(nullable = false)
	private Long difference;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime detectedAt;

	private BalanceDiscrepancy(Long walletId, Long cachedBalance, Long ledgerSum) {
		this.walletId = walletId;
		this.cachedBalance = cachedBalance;
		this.ledgerSum = ledgerSum;
		this.difference = cachedBalance - ledgerSum;
	}

	public static BalanceDiscrepancy of(Long walletId, Long cachedBalance, Long ledgerSum) {
		return new BalanceDiscrepancy(walletId, cachedBalance, ledgerSum);
	}
}
