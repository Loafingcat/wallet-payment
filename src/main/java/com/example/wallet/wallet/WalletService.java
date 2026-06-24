package com.example.wallet.wallet;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.ConcurrentChargeConflictException;
import com.example.wallet.common.exception.DuplicateIdempotencyKeyException;
import com.example.wallet.common.exception.IdempotencyKeyReusedException;
import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.ledger.LedgerType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WalletService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	// 이 메서드 전체가 하나의 트랜잭션이다: wallet.balance 갱신과 LedgerEntry insert가
	// 같이 commit되거나 같이 rollback된다. 둘 중 하나만 반영되면 절대 규칙 2번(원장 == 잔액)이 깨지므로
	// 트랜잭션 경계를 메서드 전체로 명시적으로 잡는다.
	//
	// 멱등성(ADR-003): 먼저 같은 키의 LedgerEntry가 있는지 보고, 있으면 그 결과를 그대로 반환한다.
	// 없으면 처리하고 idempotencyKey를 채워 INSERT하는데, 동시에 같은 키로 들어온 다른 요청이
	// 먼저 INSERT에 성공했다면 이 INSERT는 UNIQUE 제약 위반으로 실패한다 — 그러면 이 트랜잭션
	// 전체(잔액 변경 포함)를 롤백시키고, 컨트롤러가 먼저 커밋된 결과를 다시 조회해서 돌려준다.
	@Transactional
	public ChargeResponse charge(Long walletId, long amount, String idempotencyKey) {
		Optional<LedgerEntry> existing = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return toChargeResponse(existing.get(), idempotencyKey);
		}

		Wallet wallet = walletRepository.findById(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));

		wallet.charge(amount);

		// ADR-005: charge()는 findByIdForUpdate가 아니라 findById를 쓴다(락 없음) — Wallet의
		// @Version만으로 동시 충전을 막는다. saveAndFlush가 영속성 컨텍스트 전체를 flush하면서
		// wallet의 UPDATE ... WHERE version=?도 같이 나가는데, 동시에 같은 지갑을 충전한 다른
		// 트랜잭션이 먼저 commit했다면 여기서 ObjectOptimisticLockingFailureException이 터진다.
		// 결제 경로처럼 재시도하지 않고 바로 명확한 예외로 변환한다 — 이 트랜잭션은 통째로
		// 롤백되어 LedgerEntry가 전혀 남지 않으므로, 같은 Idempotency-Key로 재시도하면 안전하다.
		try {
			ledgerEntryRepository.saveAndFlush(LedgerEntry.charge(walletId, amount, wallet.getBalance(), idempotencyKey));
		} catch (DataIntegrityViolationException e) {
			throw new DuplicateIdempotencyKeyException(idempotencyKey, e);
		} catch (ObjectOptimisticLockingFailureException e) {
			throw new ConcurrentChargeConflictException(walletId, e);
		}

		return new ChargeResponse(wallet.getId(), wallet.getBalance());
	}

	// DuplicateIdempotencyKeyException을 잡은 컨트롤러가 호출한다. 이 시점엔 충돌을 일으킨
	// 거래가 이미 commit된 뒤이므로(UNIQUE 위반은 상대가 커밋해야 발생) 바로 조회된다.
	public ChargeResponse findChargeResultByIdempotencyKey(String idempotencyKey) {
		LedgerEntry entry = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey)
				.orElseThrow(() -> new IllegalStateException(
						"Idempotency key not found after conflict: " + idempotencyKey));
		return toChargeResponse(entry, idempotencyKey);
	}

	private ChargeResponse toChargeResponse(LedgerEntry entry, String idempotencyKey) {
		if (entry.getType() != LedgerType.CHARGE) {
			throw new IdempotencyKeyReusedException(idempotencyKey);
		}
		return new ChargeResponse(entry.getWalletId(), entry.getBalanceAfter());
	}
}
