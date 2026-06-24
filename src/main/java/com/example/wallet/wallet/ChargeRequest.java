package com.example.wallet.wallet;

import jakarta.validation.constraints.Positive;

public record ChargeRequest(@Positive(message = "충전 금액은 0보다 커야 합니다.") Long amount) {
}
