package com.example.wallet.payment.external;

import com.example.wallet.payment.Payment;

public record ExternalPaymentResponse(
		Long paymentId,
		String status,
		Long walletId,
		Long merchantId,
		Long amount,
		Long ledgerEntryId) {

	public static ExternalPaymentResponse from(Payment payment) {
		return new ExternalPaymentResponse(
				payment.getId(),
				payment.getStatus().name(),
				payment.getWalletId(),
				payment.getMerchantId(),
				payment.getAmount(),
				payment.getLedgerEntryId());
	}
}
