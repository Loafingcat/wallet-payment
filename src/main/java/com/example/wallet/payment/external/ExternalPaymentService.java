package com.example.wallet.payment.external;

import org.springframework.stereotype.Service;

import com.example.wallet.payment.Payment;

import lombok.RequiredArgsConstructor;

// 절대 규칙 6번: 외부 호출(PG 호출)을 트랜잭션 안에 두지 않는다. 그래서 이 클래스는
// @Transactional이 아니다 — DB 쓰기는 ExternalPaymentWriter의 짧은 트랜잭션들이 하고,
// 여기서는 그 사이에 PG 호출(느릴 수도, 응답이 안 올 수도 있는 호출)만 끼워 넣는다.
@Service
@RequiredArgsConstructor
public class ExternalPaymentService {

	private final ExternalPaymentWriter writer;
	private final PgClient pgClient;

	public Payment requestPayment(Long walletId, Long merchantId, long amount, String idempotencyKey,
			String simulateFailure) {
		Payment payment = writer.createPending(walletId, merchantId, amount, idempotencyKey);
		if (!payment.isPending()) {
			return payment; // 이미 결론이 난 결제(우리 쪽 멱등성) — PG를 또 부를 필요 없다.
		}

		PgApproveResult result = pgClient.approve(idempotencyKey, walletId, merchantId, amount, simulateFailure);

		return switch (result.outcome()) {
			case APPROVED -> writer.confirmApproved(payment.getId());
			case DEFINITELY_FAILED -> writer.markFailed(payment.getId(), result.detail());
			// 모른다 — PENDING_PG로 그대로 둔다. 보정(PaymentReconciliationService)이
			// 나중에 PG에 직접 물어봐서 풀어준다. 여기서 "실패"로 단정하면 안 된다.
			case UNKNOWN -> payment;
		};
	}
}
