package com.example.wallet.ledger;

// ADR-002: amount는 항상 양수, 방향은 type으로 표현한다. sign()이 그 방향을
// "잔액에 +로 더할지 -로 뺄지"로 환산한 값이다. 정합성 점검 배치(S11)가 지갑별
// 원장 합계를 계산할 때 이 값을 쓴다 — type이 늘어나도(예: 이번에 추가된 TRANSFER_*)
// 합산 로직을 매번 새로 안 짜고 여기 하나만 고치면 된다.
public enum LedgerType {
	CHARGE(1),
	PAYMENT(-1),
	REFUND(1),
	SETTLEMENT_FEE(-1),
	// S11: 지갑 간 송금. 보내는 쪽엔 TRANSFER_OUT(감소), 받는 쪽엔 TRANSFER_IN(증가)이
	// 각자의 지갑 원장에 기록된다 — 결제처럼 가맹점이 끼는 게 아니라 지갑 대 지갑이라
	// PAYMENT/CHARGE와는 별도 타입으로 둔다.
	TRANSFER_OUT(-1),
	TRANSFER_IN(1);

	private final int sign;

	LedgerType(int sign) {
		this.sign = sign;
	}

	public int sign() {
		return sign;
	}
}
