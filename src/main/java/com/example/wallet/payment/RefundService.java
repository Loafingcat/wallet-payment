package com.example.wallet.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.DuplicateIdempotencyKeyException;
import com.example.wallet.common.exception.IdempotencyKeyReusedException;
import com.example.wallet.common.exception.PaymentNotFoundException;
import com.example.wallet.common.exception.RefundExceedsPaymentException;
import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.ledger.LedgerType;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;

import lombok.RequiredArgsConstructor;

// 원장 불변 원칙(절대 규칙 2번): 환불은 원래 PAYMENT 거래를 고치지 않고, 반대 방향의 새
// REFUND 거래로 표현한다. "이 결제를 얼마나 환불했는지"는 REFUND 거래들을 모아서 계산하고,
// PAYMENT 거래 자체는 절대 건드리지 않는다.
//
// 환불도 잔액을 바꾸는 작업이라 PaymentService.pay()와 같은 지갑 락(findByIdForUpdate)을 쓴다.
// 덕분에 "이 결제, 지금까지 얼마나 환불됐지?"를 확인하는 시점과 새 REFUND를 기록하는 시점 사이에
// 다른 환불이 끼어들 수 없다 — S2에서 결제 이중차감을 막으려고 추가한 락이, 여기서는 환불 누적액
// 초과를 막는 데도 그대로 쓰인다(같은 지갑 row를 잠그기 때문).
@Service
@RequiredArgsConstructor
public class RefundService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	@Transactional
	public RefundResponse refund(Long paymentId, long amount, String idempotencyKey) {
		Optional<LedgerEntry> existing = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return toRefundResponse(existing.get(), idempotencyKey);
		}

		LedgerEntry payment = ledgerEntryRepository.findById(paymentId)
				.filter(entry -> entry.getType() == LedgerType.PAYMENT)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		Wallet wallet = walletRepository.findByIdForUpdate(payment.getWalletId())
				.orElseThrow(() -> new WalletNotFoundException(payment.getWalletId()));

		long alreadyRefunded = sumRefunded(paymentId);
		if (alreadyRefunded + amount > payment.getAmount()) {
			throw new RefundExceedsPaymentException(paymentId, alreadyRefunded, payment.getAmount());
		}

		wallet.charge(amount); // 환불은 충전과 같은 방향(잔액 증가)이다.

		LedgerEntry refundEntry;
		try {
			refundEntry = ledgerEntryRepository.saveAndFlush(
					LedgerEntry.refund(payment.getWalletId(), payment.getMerchantId(), amount, wallet.getBalance(),
							paymentId, idempotencyKey));
		} catch (DataIntegrityViolationException e) {
			throw new DuplicateIdempotencyKeyException(idempotencyKey, e);
		}

		long totalRefunded = alreadyRefunded + amount;
		return new RefundResponse(refundEntry.getRefundOfEntryId(), refundEntry.getAmount(), totalRefunded,
				wallet.getBalance());
	}

	// DuplicateIdempotencyKeyException을 잡은 컨트롤러가 호출한다.
	public RefundResponse findRefundResultByIdempotencyKey(String idempotencyKey) {
		LedgerEntry entry = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey)
				.orElseThrow(() -> new IllegalStateException(
						"Idempotency key not found after conflict: " + idempotencyKey));
		return toRefundResponse(entry, idempotencyKey);
	}

	private long sumRefunded(Long paymentId) {
		List<LedgerEntry> refunds = ledgerEntryRepository.findByRefundOfEntryId(paymentId);
		return refunds.stream().mapToLong(LedgerEntry::getAmount).sum();
	}

	private RefundResponse toRefundResponse(LedgerEntry entry, String idempotencyKey) {
		if (entry.getType() != LedgerType.REFUND) {
			throw new IdempotencyKeyReusedException(idempotencyKey);
		}
		long totalRefunded = sumRefunded(entry.getRefundOfEntryId());
		return new RefundResponse(entry.getRefundOfEntryId(), entry.getAmount(), totalRefunded,
				entry.getBalanceAfter());
	}
}
