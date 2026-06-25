package com.example.wallet.payment.external;

public record PgApproveResponseBody(String status, String idempotencyKey) {
}
