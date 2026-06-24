package com.example.wallet.payment;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.DuplicateIdempotencyKeyException;
import com.example.wallet.common.exception.IdempotencyKeyReusedException;
import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.ledger.LedgerType;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;

import lombok.RequiredArgsConstructor;

// 절대 규칙 3번: 동시 결제는 같은 지갑에 대해 직렬화된다. findByIdForUpdate가 SELECT ... FOR
// UPDATE로 행을 잠그기 때문에, 두 번째 트랜잭션은 첫 번째가 commit(or rollback)할 때까지
// 이 메서드의 첫 줄에서 그냥 멈춰 있다가, 깨어난 뒤에는 갱신된 balance를 보고 다시 확인한다.
// 그래서 "확인했던 값이 쓰는 시점엔 이미 낡은 값"이 되는 일이 없다.
//
// 멱등성(ADR-003): WalletService.charge와 같은 패턴. 같은 키의 LedgerEntry가 있으면 그 결과를
// 그대로 반환하고, 없으면 처리 후 idempotencyKey를 채워 INSERT한다. 동시에 같은 키로 들어온
// 다른 요청이 먼저 커밋했다면 이 INSERT는 UNIQUE 제약 위반으로 실패하고, 이 트랜잭션 전체
// (지갑 락 + 잔액 차감 포함)가 롤백된다.
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	@Transactional
	public PaymentResponse pay(Long walletId, Long merchantId, long amount, String idempotencyKey) {
		Optional<LedgerEntry> existing = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return toPaymentResponse(existing.get(), idempotencyKey);
		}

		Wallet wallet = walletRepository.findByIdForUpdate(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));

		wallet.pay(amount);

		try {
			ledgerEntryRepository.saveAndFlush(
					LedgerEntry.payment(walletId, merchantId, amount, wallet.getBalance(), idempotencyKey));
		} catch (DataIntegrityViolationException e) {
			throw new DuplicateIdempotencyKeyException(idempotencyKey, e);
		}

		return new PaymentResponse(walletId, merchantId, wallet.getBalance());
	}

	// DuplicateIdempotencyKeyException을 잡은 컨트롤러가 호출한다.
	public PaymentResponse findPaymentResultByIdempotencyKey(String idempotencyKey) {
		LedgerEntry entry = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey)
				.orElseThrow(() -> new IllegalStateException(
						"Idempotency key not found after conflict: " + idempotencyKey));
		return toPaymentResponse(entry, idempotencyKey);
	}

	private PaymentResponse toPaymentResponse(LedgerEntry entry, String idempotencyKey) {
		if (entry.getType() != LedgerType.PAYMENT) {
			throw new IdempotencyKeyReusedException(idempotencyKey);
		}
		return new PaymentResponse(entry.getWalletId(), entry.getMerchantId(), entry.getBalanceAfter());
	}
}
