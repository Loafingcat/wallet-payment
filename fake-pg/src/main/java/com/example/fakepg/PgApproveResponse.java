package com.example.fakepg;

public record PgApproveResponse(String status, String idempotencyKey) {
}
