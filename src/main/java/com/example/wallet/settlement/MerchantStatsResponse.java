package com.example.wallet.settlement;

import java.time.LocalDate;

public record MerchantStatsResponse(
		Long merchantId,
		LocalDate from,
		LocalDate to,
		Long totalPaymentAmount,
		Long totalRefundAmount,
		Long netAmount) {

	public static MerchantStatsResponse of(MerchantAggregate aggregate, LocalDate from, LocalDate to) {
		long net = aggregate.totalPaymentAmount() - aggregate.totalRefundAmount();
		return new MerchantStatsResponse(aggregate.merchantId(), from, to,
				aggregate.totalPaymentAmount(), aggregate.totalRefundAmount(), net);
	}
}
