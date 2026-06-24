package com.example.wallet.settlement;

public record MerchantDailyAggregate(Long merchantId, Long totalPaymentAmount, Long totalRefundAmount) {
}
