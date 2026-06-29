package com.example.wallet.reconciliation;

public record BalanceDiscrepancyResponse(
		Long walletId,
		Long cachedBalance,
		Long ledgerSum,
		Long difference) {

	public static BalanceDiscrepancyResponse from(BalanceDiscrepancy discrepancy) {
		return new BalanceDiscrepancyResponse(
				discrepancy.getWalletId(),
				discrepancy.getCachedBalance(),
				discrepancy.getLedgerSum(),
				discrepancy.getDifference());
	}
}
