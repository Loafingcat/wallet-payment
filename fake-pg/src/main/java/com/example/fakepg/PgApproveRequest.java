package com.example.fakepg;

public record PgApproveRequest(Long walletId, Long merchantId, Long amount) {
}
