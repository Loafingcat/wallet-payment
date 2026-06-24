package com.example.wallet.common.exception;

// ADR-005: 충전은 비관적 락 없이 @Version만 믿는다. 동시에 같은 지갑을 충전하면 늦게
// flush되는 쪽이 ObjectOptimisticLockingFailureException을 받는데, 그걸 그대로 흘려서
// 일관성 없는 500을 내는 대신 이 예외로 변환해 명확한 409를 준다. 이 트랜잭션은 통째로
// 롤백되어 LedgerEntry가 전혀 남지 않으므로, 클라이언트가 같은 Idempotency-Key로 재시도하면
// 이중 충전 없이 안전하게 끝난다.
public class ConcurrentChargeConflictException extends RuntimeException {

	public ConcurrentChargeConflictException(Long walletId, Throwable cause) {
		super("Concurrent charge conflict, retry with the same Idempotency-Key: walletId=" + walletId, cause);
	}
}
