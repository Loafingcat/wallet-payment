package com.example.wallet.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BalanceReconciliationScheduler {

	private final BalanceReconciliationService balanceReconciliationService;

	// 매일 새벽 2시, 모든 지갑의 캐시 잔액과 원장 합계가 일치하는지 검사한다.
	@Scheduled(cron = "0 0 2 * * *")
	public void runDailyReconciliation() {
		balanceReconciliationService.reconcile();
	}
}
