package com.example.wallet.wallet;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.DuplicateIdempotencyKeyException;
import com.example.wallet.common.exception.InvalidTransferException;
import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

// S11-2: 두 지갑을 잠그는 순서를 항상 "지갑 id 오름차순"으로 고정한다. A→B든 B→A든,
// 두 지갑 중 id가 더 작은 쪽을 항상 먼저 잠근다 — 그래서 같은 지갑 쌍을 동시에 건드리는
// 모든 송금이 항상 같은 순서로 락을 요청하게 되고, "내가 잡은 락을 상대가 기다리고, 상대가
// 잡은 락을 내가 기다리는" 순환 대기 자체가 구조적으로 생길 수 없다(데드락 재현은
// TransferDeadlockTest의 이전 버전 참고, git log로 확인 가능).
//
// 그래도 한쪽이 다른 트랜잭션에 막혀 오래 대기할 가능성은 남아있어서(데드락이 아니라
// 단순 대기), MySQL의 innodb_lock_wait_timeout을 이 트랜잭션(세션)에서만 짧게 줄여서
// 대기 시간에 상한을 둔다. (jakarta.persistence.lock.timeout JPA 힌트는 MySQL
// 다이얼렉트에서 실제로 적용되지 않아서 — 직접 검증해서 확인 — 세션 변수를 직접 쓴다.)
@Service
@RequiredArgsConstructor
public class TransferService {

	private static final int LOCK_WAIT_TIMEOUT_SECONDS = 3;

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final EntityManager entityManager;

	@Transactional
	public TransferResponse transfer(Long fromWalletId, Long toWalletId, long amount, String idempotencyKey) {
		if (fromWalletId.equals(toWalletId)) {
			throw new InvalidTransferException("같은 지갑으로는 송금할 수 없습니다: walletId=" + fromWalletId);
		}

		String outKey = idempotencyKey + ":out";
		String inKey = idempotencyKey + ":in";

		Optional<LedgerEntry> existingOut = ledgerEntryRepository.findByIdempotencyKey(outKey);
		if (existingOut.isPresent()) {
			LedgerEntry out = existingOut.get();
			LedgerEntry in = ledgerEntryRepository.findByIdempotencyKey(inKey)
					.orElseThrow(() -> new IllegalStateException("Transfer in-leg missing for " + inKey));
			return new TransferResponse(fromWalletId, toWalletId, amount, out.getBalanceAfter(), in.getBalanceAfter());
		}

		// 이 트랜잭션이 쓰는 커넥션(세션)에서만 락 대기 한도를 줄인다 — 다른 트랜잭션/세션엔
		// 영향을 주지 않는다.
		entityManager.createNativeQuery("SET innodb_lock_wait_timeout = " + LOCK_WAIT_TIMEOUT_SECONDS)
				.executeUpdate();

		// 항상 id가 작은 지갑을 먼저 잠근다 — fromWalletId/toWalletId 어느 쪽이 더 작든,
		// 이 지갑 쌍에 대한 모든 송금(양방향)이 항상 같은 순서로 락을 요청하게 만든다.
		Long firstLockId = Math.min(fromWalletId, toWalletId);
		Long secondLockId = Math.max(fromWalletId, toWalletId);

		Wallet first = walletRepository.findByIdForUpdate(firstLockId)
				.orElseThrow(() -> new WalletNotFoundException(firstLockId));
		Wallet second = walletRepository.findByIdForUpdate(secondLockId)
				.orElseThrow(() -> new WalletNotFoundException(secondLockId));

		Wallet fromWallet = firstLockId.equals(fromWalletId) ? first : second;
		Wallet toWallet = firstLockId.equals(toWalletId) ? first : second;

		fromWallet.pay(amount);
		toWallet.charge(amount);

		try {
			ledgerEntryRepository.saveAndFlush(
					LedgerEntry.transferOut(fromWalletId, amount, fromWallet.getBalance(), outKey));
			ledgerEntryRepository.saveAndFlush(
					LedgerEntry.transferIn(toWalletId, amount, toWallet.getBalance(), inKey));
			return new TransferResponse(fromWalletId, toWalletId, amount, fromWallet.getBalance(),
					toWallet.getBalance());
		} catch (DataIntegrityViolationException e) {
			throw new DuplicateIdempotencyKeyException(idempotencyKey, e);
		}
	}

	// DuplicateIdempotencyKeyException을 잡은 컨트롤러가 호출한다.
	public TransferResponse findTransferResultByIdempotencyKey(Long fromWalletId, Long toWalletId, long amount,
			String idempotencyKey) {
		LedgerEntry out = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey + ":out")
				.orElseThrow(() -> new IllegalStateException(
						"Idempotency key not found after conflict: " + idempotencyKey));
		LedgerEntry in = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey + ":in")
				.orElseThrow(() -> new IllegalStateException(
						"Idempotency key not found after conflict: " + idempotencyKey));
		return new TransferResponse(fromWalletId, toWalletId, amount, out.getBalanceAfter(), in.getBalanceAfter());
	}
}
