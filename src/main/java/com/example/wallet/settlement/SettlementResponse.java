package com.example.wallet.settlement;

import java.time.LocalDate;

public record SettlementResponse(
		Long merchantId,
		LocalDate settlementDate,
		Long totalPaymentAmount,
		Long totalRefundAmount,
		Long feeAmount,
		Long settlementAmount) {

	public static SettlementResponse from(Settlement settlement) {
		return new SettlementResponse(
				settlement.getMerchantId(),
				settlement.getSettlementDate(),
				settlement.getTotalPaymentAmount(),
				settlement.getTotalRefundAmount(),
				settlement.getFeeAmount(),
				settlement.getSettlementAmount());
	}
}
