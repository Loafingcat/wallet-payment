package com.example.wallet.wallet;

public record TransferResponse(
		Long fromWalletId,
		Long toWalletId,
		Long amount,
		Long fromWalletBalance,
		Long toWalletBalance) {
}
