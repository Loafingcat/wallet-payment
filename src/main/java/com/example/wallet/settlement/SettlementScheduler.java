package com.example.wallet.settlement;

import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SettlementScheduler {

	private final SettlementBatchRunner settlementBatchRunner;

	// 매일 새벽 1시, "어제" 하루치를 전체 가맹점 대상으로 정산한다.
	@Scheduled(cron = "0 0 1 * * *")
	public void runDailySettlement() {
		settlementBatchRunner.run(LocalDate.now().minusDays(1), null);
	}
}
