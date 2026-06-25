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

import lombok.RequiredArgsConstructor;

// S11 1단계: 두 지갑 모두 잠가야 하는데, 잠그는 순서가 "인자로 받은 순서"(fromWalletId
// 먼저, toWalletId 다음)다. A→B 송금과 B→A 송금이 동시에 들어오면, 하나는 A를 먼저 잠그고
// B를 기다리고, 다른 하나는 B를 먼저 잠그고 A를 기다리는 순환 대기(circular wait)가
// 생길 수 있다 — 데드락이다. TransferDeadlockTest로 의도적으로 재현한다(별도 커밋,
// "데드락 재현"). 다음 커밋에서 잠금 순서를 지갑 id 오름차순으로 고정해 해결한다.
@Service
@RequiredArgsConstructor
public class TransferService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

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

		// 의도적으로 정렬하지 않는다 — 인자 순서 그대로 잠근다(버그 재현용).
		Wallet fromWallet = walletRepository.findByIdForUpdate(fromWalletId)
				.orElseThrow(() -> new WalletNotFoundException(fromWalletId));
		Wallet toWallet = walletRepository.findByIdForUpdate(toWalletId)
				.orElseThrow(() -> new WalletNotFoundException(toWalletId));

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
