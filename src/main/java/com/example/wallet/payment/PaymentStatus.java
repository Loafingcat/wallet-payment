package com.example.wallet.payment;

public enum PaymentStatus {
	// PG 호출 결과를 아직 확실히 모른다 — 호출 자체를 안 했거나, 호출했는데 타임아웃/
	// 응답유실로 결과를 못 받았거나, 둘 다 보정(reconciliation)이 풀어줘야 하는 상태.
	PENDING_PG,
	// PG가 승인을 확인해줬고, 그 결과로 LedgerEntry가 이미 기록됐다(Payment.ledgerEntryId 참고).
	APPROVED,
	// PG가 명확히 거부했거나(5xx), 보정이 유예 기간을 넘겨도 끝내 승인 기록을 찾지 못했다.
	FAILED
}
