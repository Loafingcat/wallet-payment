package com.example.wallet.settlement;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// SettlementBatchRunner는 "정확히 하루"만 집계하지만, 이 서비스는 임의의 기간을 받는다 —
// SettlementQueryRepository.aggregate()가 처음부터 기간을 매개변수로 받게 만들어둔 덕분에
// (S5에서 "동적 기간" 요구사항 때문에 그렇게 설계했다) 새 쿼리를 안 짜고 그대로 재사용한다.
@Service
@RequiredArgsConstructor
public class MerchantStatsService {

	private final SettlementQueryRepository settlementQueryRepository;

	public MerchantStatsResponse statsFor(Long merchantId, LocalDate from, LocalDate to) {
		List<MerchantAggregate> aggregates = settlementQueryRepository.aggregate(
				from.atStartOfDay(), to.plusDays(1).atStartOfDay(), merchantId);

		MerchantAggregate aggregate = aggregates.stream()
				.findFirst()
				.orElse(new MerchantAggregate(merchantId, 0L, 0L));

		return MerchantStatsResponse.of(aggregate, from, to);
	}

	public List<MerchantStatsResponse> statsForAllMerchants(LocalDate from, LocalDate to) {
		List<MerchantAggregate> aggregates = settlementQueryRepository.aggregate(
				from.atStartOfDay(), to.plusDays(1).atStartOfDay(), null);

		return aggregates.stream()
				.map(aggregate -> MerchantStatsResponse.of(aggregate, from, to))
				.toList();
	}
}
