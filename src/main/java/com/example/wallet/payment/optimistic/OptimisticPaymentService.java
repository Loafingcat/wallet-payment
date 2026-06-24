package com.example.wallet.payment.optimistic;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// 비관적 락(PaymentService)과의 비교용 대안 구현. 자세한 트레이드오프는
// docs/optimistic-vs-pessimistic-lock.md, 실측 비교는 docs/benchmark.md 참고.
//
// 이 클래스는 @Transactional이 아니다 — payOnce()가 @Transactional인데, 같은 클래스
// 안에서 this.payOnce()로 불렀다면 Spring AOP 프록시를 안 거쳐서 트랜잭션이 안 걸렸을
// 것이다(S3~S6에서 반복해서 마주친 self-invocation 문제). 그래서 실제 DB 작업은 별도
// 빈(OptimisticPaymentWriter)에 두고, 여기서는 그 빈을 외부에서 호출하며 재시도만 한다.
@Service
@RequiredArgsConstructor
public class OptimisticPaymentService {

	// 재시도/백오프 정책 — S8 부하 테스트 결과 해석 시 이 값들을 같이 기록한다.
	static final int MAX_ATTEMPTS = 5;
	static final long BASE_BACKOFF_MILLIS = 10L;

	private final OptimisticPaymentWriter writer;

	public OptimisticPaymentResult pay(Long walletId, Long merchantId, long amount, String idempotencyKey) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return new OptimisticPaymentResult(
						writer.payOnce(walletId, merchantId, amount, idempotencyKey), attempt);
			} catch (ObjectOptimisticLockingFailureException e) {
				if (attempt == MAX_ATTEMPTS) {
					throw e;
				}
				backoff(attempt);
				// 재시도: 다음 시도에서 wallet을 처음부터 다시 읽는다. 그 사이에 이긴 쪽이
				// 이미 commit했으므로, 이번에는 최신 balance를 보고 다시 판단하게 된다
				// (그래서 "잔액 부족"처럼 진짜 비즈니스 사유로 실패하는 경우 재시도 1번
				// 만에 InsufficientBalanceException으로 끝나는 게 보통이다).
			}
		}
		throw new IllegalStateException("unreachable");
	}

	// 선형 백오프(attempt번째 재시도 전 10ms * attempt만큼 쉰다). 충돌 직후 곧바로 다시
	// 읽으면 상대 트랜잭션이 아직 commit 중일 수 있어서 또 충돌할 확률이 높다 — 아주
	// 짧게라도 쉬어서 그 확률을 낮춘다.
	private void backoff(int attempt) {
		try {
			Thread.sleep(BASE_BACKOFF_MILLIS * attempt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
