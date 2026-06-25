package com.example.wallet.payment.external;

// fake-pg 모듈의 PgApproveRequest와 모양은 같지만, 일부러 같은 클래스를 공유하지 않는다.
// 실제로 분리된 두 서비스라면 자바 클래스를 공유할 수 없고 HTTP/JSON 계약만 공유하기
// 때문이다 — 이 프로젝트에서도 그 경계를 그대로 지킨다(테스트에서만 fake-pg 모듈에
// 의존하고, 운영 코드는 의존하지 않는다).
public record PgApproveRequestBody(Long walletId, Long merchantId, Long amount) {
}
