package com.example.wallet.wallet;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
		@NotNull Long fromWalletId,
		@NotNull Long toWalletId,
		@Positive(message = "송금 금액은 0보다 커야 합니다.") Long amount) {
}
