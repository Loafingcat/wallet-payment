package com.example.wallet.payment.external;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ExternalPaymentRequest(
		@NotNull Long walletId,
		@NotNull Long merchantId,
		@Positive(message = "결제 금액은 0보다 커야 합니다.") Long amount) {
}
