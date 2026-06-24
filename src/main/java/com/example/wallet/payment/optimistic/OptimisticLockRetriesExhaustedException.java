package com.example.wallet.payment.optimistic;

// OptimisticPaymentService가 MAX_ATTEMPTS번을 다 써도 충돌이 안 풀렸을 때 던진다.
// 단순히 원인 예외(ObjectOptimisticLockingFailureException)를 그대로 흘리는 대신 이걸로
// 감싸는 이유는, 몇 번 시도하고 포기했는지(attempts)를 호출자(GlobalExceptionHandler)가
// 알 수 있게 하기 위해서다 — 모니터링/부하 테스트(S8)에서 "재시도가 정말 소진돼서 실패한
// 비율"을 응답 헤더로 그대로 노출할 수 있다.
public class OptimisticLockRetriesExhaustedException extends RuntimeException {

	private final int attempts;

	public OptimisticLockRetriesExhaustedException(int attempts, Throwable cause) {
		super("Optimistic lock retries exhausted after " + attempts + " attempts", cause);
		this.attempts = attempts;
	}

	public int getAttempts() {
		return attempts;
	}
}
