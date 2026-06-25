package com.example.wallet.payment.external;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

	private final PaymentReconciliationService reconciliationService;

	// 10초마다 PENDING_PG로 남은 결제를 훑어서 PG에 직접 물어본다.
	@Scheduled(fixedDelay = 10_000)
	public void reconcile() {
		reconciliationService.reconcilePending();
	}
}
