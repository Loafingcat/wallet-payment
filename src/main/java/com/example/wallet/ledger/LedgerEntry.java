package com.example.wallet.ledger;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

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

// 거래 원장은 불변이다. UPDATE/DELETE를 절대 하지 않는다 — 취소/환불은 반대 방향의 새 LedgerEntry로 표현한다.
// 그래서 이 엔티티에는 setter가 없고, 생성 후 상태를 바꿀 수 있는 메서드도 없다.
@Entity
@Table(name = "ledger_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long walletId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private LedgerType type;

	@Column(nullable = false)
	private Long amount;

	@Column(nullable = false)
	private Long balanceAfter;

	// 결제(PAYMENT)·환불(REFUND) 거래에만 채워진다. 충전(CHARGE)에는 가맹점이 없으므로 null.
	@Column
	private Long merchantId;

	// REFUND 거래에만 채워진다. 환불 대상 PAYMENT LedgerEntry의 id를 가리킨다.
	// "이 결제에 대해 지금까지 환불된 합계"를 구할 때 이 필드로 PAYMENT를 거꾸로 찾는다.
	@Column
	private Long refundOfEntryId;

	// 클라이언트가 보낸 Idempotency-Key. UNIQUE 제약이 "같은 키로 두 번 성공할 수 없다"를
	// DB 레벨에서 보장해준다(ADR-003).
	@Column(nullable = false, unique = true)
	private String idempotencyKey;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private LedgerEntry(Long walletId, LedgerType type, Long amount, Long balanceAfter, Long merchantId,
			Long refundOfEntryId, String idempotencyKey) {
		this.walletId = walletId;
		this.type = type;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.merchantId = merchantId;
		this.refundOfEntryId = refundOfEntryId;
		this.idempotencyKey = idempotencyKey;
	}

	public static LedgerEntry charge(Long walletId, long amount, long balanceAfter, String idempotencyKey) {
		return new LedgerEntry(walletId, LedgerType.CHARGE, amount, balanceAfter, null, null, idempotencyKey);
	}

	public static LedgerEntry payment(Long walletId, Long merchantId, long amount, long balanceAfter,
			String idempotencyKey) {
		return new LedgerEntry(walletId, LedgerType.PAYMENT, amount, balanceAfter, merchantId, null, idempotencyKey);
	}

	public static LedgerEntry refund(Long walletId, Long merchantId, long amount, long balanceAfter,
			Long refundOfEntryId, String idempotencyKey) {
		return new LedgerEntry(walletId, LedgerType.REFUND, amount, balanceAfter, merchantId, refundOfEntryId,
				idempotencyKey);
	}

	public static LedgerEntry transferOut(Long walletId, long amount, long balanceAfter, String idempotencyKey) {
		return new LedgerEntry(walletId, LedgerType.TRANSFER_OUT, amount, balanceAfter, null, null, idempotencyKey);
	}

	public static LedgerEntry transferIn(Long walletId, long amount, long balanceAfter, String idempotencyKey) {
		return new LedgerEntry(walletId, LedgerType.TRANSFER_IN, amount, balanceAfter, null, null, idempotencyKey);
	}

	// "이 거래가 잔액에 실제로 더한/뺀 값". type.sign()이 방향을 알고 있어서, 호출하는 쪽이
	// type별로 분기할 필요가 없다 — 정합성 점검 배치(S11)가 지갑별 합계를 구할 때 쓴다.
	public long signedAmount() {
		return amount * type.sign();
	}
}
