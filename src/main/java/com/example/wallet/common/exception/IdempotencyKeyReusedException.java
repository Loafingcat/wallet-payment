package com.example.wallet.common.exception;

// 같은 Idempotency-Key가 이미 다른 종류의 거래(예: 충전)에 쓰인 적이 있는데, 결제처럼 다른
// 거래에 재사용된 경우. 멱등성은 "같은 요청"을 다시 보냈을 때만 적용되어야 하므로, 다른 요청에
// 키가 잘못 재사용된 경우는 그냥 캐시된 결과를 돌려주면 안 되고 명확히 에러로 알려야 한다.
public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException(String idempotencyKey) {
		super("Idempotency key already used for a different operation: " + idempotencyKey);
	}
}
