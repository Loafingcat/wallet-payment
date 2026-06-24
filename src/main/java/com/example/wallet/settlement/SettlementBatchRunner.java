package com.example.wallet.settlement;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.wallet.common.exception.DuplicateSettlementException;

import lombok.RequiredArgsConstructor;

// SettlementService.settleOne()은 @Transactional이라, 이 클래스 안에서 this.settleOne()처럼
// 직접 호출하면 Spring AOP 프록시를 거치지 않아 트랜잭션이 적용되지 않는다(self-invocation
// 문제). 그래서 정산 로직을 SettlementService라는 별도 빈으로 분리하고, 여기서는 그 빈을
// 주입받아 외부에서 호출하는 형태로 가맹점별 루프를 돈다.
@Component
@RequiredArgsConstructor
public class SettlementBatchRunner {

	private final SettlementQueryRepository settlementQueryRepository;
	private final SettlementService settlementService;

	// merchantId가 null이면 그 날 거래(결제/환불)가 있었던 모든 가맹점을 정산한다.
	public List<Settlement> run(LocalDate date, Long merchantId) {
		LocalDateTime from = date.atStartOfDay();
		LocalDateTime to = date.plusDays(1).atStartOfDay();

		List<MerchantDailyAggregate> aggregates = settlementQueryRepository.aggregate(from, to, merchantId);

		return aggregates.stream()
				.map(aggregate -> settleWithFallback(date, aggregate))
				.toList();
	}

	private Settlement settleWithFallback(LocalDate date, MerchantDailyAggregate aggregate) {
		try {
			return settlementService.settleOne(date, aggregate);
		} catch (DuplicateSettlementException e) {
			return settlementService.findSettlement(aggregate.merchantId(), date);
		}
	}
}
