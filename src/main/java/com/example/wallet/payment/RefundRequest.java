package com.example.wallet.payment;

import jakarta.validation.constraints.Positive;

public record RefundRequest(@Positive(message = "환불 금액은 0보다 커야 합니다.") Long amount) {
}
