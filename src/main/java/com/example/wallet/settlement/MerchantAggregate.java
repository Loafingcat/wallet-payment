package com.example.wallet.settlement;

// 하루치(정산 배치)든, 임의의 기간(통계 API)이든 똑같이 쓰는 "기간 동안의 가맹점별 합계".
public record MerchantAggregate(Long merchantId, Long totalPaymentAmount, Long totalRefundAmount) {
}
