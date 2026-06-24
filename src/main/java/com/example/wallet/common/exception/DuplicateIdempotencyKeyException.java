package com.example.wallet.common.exception;

// 같은 Idempotency-Key로 동시에 두 요청이 들어와서, 둘 다 "키가 아직 없다"고 보고 처리를
// 시도한 경우 늦게 INSERT를 시도한 쪽에서 던진다. 호출하는 쪽(WalletService/PaymentService)이
// 이 예외를 잡아서 먼저 커밋된 거래의 결과를 다시 조회해 반환한다.
public class DuplicateIdempotencyKeyException extends RuntimeException {

	public DuplicateIdempotencyKeyException(String idempotencyKey, Throwable cause) {
		super("Duplicate idempotency key: " + idempotencyKey, cause);
	}
}
