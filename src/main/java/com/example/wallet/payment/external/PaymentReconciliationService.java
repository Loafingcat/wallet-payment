package com.example.wallet.payment.external;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.wallet.payment.Payment;
import com.example.wallet.payment.PaymentRepository;
import com.example.wallet.payment.PaymentStatus;

import lombok.RequiredArgsConstructor;

// PENDING_PG로 남은 결제를 PG에 직접 물어봐서 결론을 낸다.
//
// 왜 "PG에 기록이 없다(NOT_FOUND)"를 곧바로 실패로 단정하지 않는가: timeout 시나리오에서는
// 우리가 포기한 시점에 PG가 "아직" 처리 전이었을 뿐, 조금 있으면 승인될 수도 있다. 너무
// 일찍 실패로 단정하면, 사실은 성공할 결제를 우리가 먼저 죽여버리는 거짓 음성(false
// negative)이 생긴다. 그래서 유예 기간(gracePeriod)을 두고, 그 안에는 NOT_FOUND를
// "아직 모름"으로 취급해 PENDING_PG를 유지한다. 유예 기간을 넘기고도 NOT_FOUND면 그때야
// "정말 안 됐다"고 보고 FAILED로 확정한다.
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

	private final PaymentRepository paymentRepository;
	private final ExternalPaymentWriter writer;
	private final PgClient pgClient;

	@Value("${payment.reconciliation.grace-period-ms:10000}")
	private long gracePeriodMs;

	public Payment reconcileOne(Long paymentId) {
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new IllegalStateException("Payment not found: id=" + paymentId));
		if (!payment.isPending()) {
			return payment;
		}

		PgQueryResult result = pgClient.queryStatus(payment.getIdempotencyKey());
		log.info("[보정] paymentId={}, pgResult={}", paymentId, result);

		if (result == PgQueryResult.APPROVED) {
			return writer.confirmApproved(payment.getId());
		}
		if (result == PgQueryResult.NOT_FOUND && isOlderThanGracePeriod(payment)) {
			return writer.markFailed(payment.getId(),
					"PG에 승인 기록 없음 (유예 기간 " + gracePeriodMs + "ms 경과 후 보정 확인)");
		}
		// NOT_FOUND이지만 유예 기간 안이거나, PG 조회 자체가 실패(UNKNOWN) — 다음
		// 스케줄에서 다시 확인한다.
		return payment;
	}

	public List<Payment> reconcilePending() {
		return paymentRepository.findByStatus(PaymentStatus.PENDING_PG).stream()
				.map(p -> reconcileOne(p.getId()))
				.toList();
	}

	private boolean isOlderThanGracePeriod(Payment payment) {
		return payment.getCreatedAt().isBefore(LocalDateTime.now().minus(Duration.ofMillis(gracePeriodMs)));
	}
}
