package com.example.wallet.payment.external;

// PG 호출의 결과는 세 갈래다 — "성공", "확실히 실패", "모름". 이 셋을 구분하는 게 이
// 모듈의 핵심이다. 흔한 실수는 "성공 응답을 못 받음 == 실패"로 합쳐버리는 것인데, 그러면
// 타임아웃/응답유실 때문에 실제로는 성공한 결제를 우리가 실패로 잘못 처리하게 된다.
public record PgApproveResult(Outcome outcome, String detail) {

	public enum Outcome {
		APPROVED,
		DEFINITELY_FAILED,
		UNKNOWN
	}

	public static PgApproveResult approved() {
		return new PgApproveResult(Outcome.APPROVED, null);
	}

	public static PgApproveResult definitelyFailed(String detail) {
		return new PgApproveResult(Outcome.DEFINITELY_FAILED, detail);
	}

	public static PgApproveResult unknown(String detail) {
		return new PgApproveResult(Outcome.UNKNOWN, detail);
	}
}
