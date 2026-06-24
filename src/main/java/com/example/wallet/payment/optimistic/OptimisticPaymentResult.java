package com.example.wallet.payment.optimistic;

import com.example.wallet.payment.PaymentResponse;

// attempts: 성공(또는 최종 실패)까지 걸린 시도 횟수. 1이면 충돌 없이 한 번에 끝났다는 뜻이고,
// 2 이상이면 그만큼 ObjectOptimisticLockingFailureException으로 재시도했다는 뜻이다.
// 부하 테스트(S8)에서 재시도율을 측정하려고 이 값을 HTTP 응답 헤더로 그대로 내보낸다.
public record OptimisticPaymentResult(PaymentResponse response, int attempts) {
}
