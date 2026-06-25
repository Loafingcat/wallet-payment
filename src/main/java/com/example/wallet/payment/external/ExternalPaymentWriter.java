package com.example.wallet.payment.external;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.payment.Payment;
import com.example.wallet.payment.PaymentRepository;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;

import lombok.RequiredArgsConstructor;

// 짧고 확정적인 DB 트랜잭션 세 개로 나눠둔다(createPending/confirmApproved/markFailed).
// ExternalPaymentService(오케스트레이터)가 이 사이에 PG HTTP 호출을 끼워 넣는데, 그
// 호출이 이 클래스 메서드들 "안"에 들어가면 절대 규칙 6번을 어기게 된다 — 그래서 별도
// 빈으로 분리했다(S3~S8에서 반복된 self-invocation 회피 패턴과 같은 이유로도 분리 필요).
@Component
@RequiredArgsConstructor
public class ExternalPaymentWriter {

	private final PaymentRepository paymentRepository;
	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	// PG를 부르기 "전에" 이 트랜잭션이 먼저 커밋된다. 그래야 우리 프로세스가 PG 호출
	// 도중에 죽어도 "이 결제를 시도했었다"는 사실 자체는 살아남고, 나중에 보정이 이
	// 행을 찾아서 마무리할 수 있다.
	@Transactional
	public Payment createPending(Long walletId, Long merchantId, long amount, String idempotencyKey) {
		return paymentRepository.findByIdempotencyKey(idempotencyKey)
				.orElseGet(() -> paymentRepository.save(new Payment(walletId, merchantId, amount, idempotencyKey)));
	}

	// PG가 승인을 확인해줬을 때만 호출된다. 여기서 비로소 지갑을 잠그고 차감하고
	// LedgerEntry를 쓴다 — "성공이 확인된 후"에만 돈을 움직인다는 원칙.
	@Transactional
	public Payment confirmApproved(Long paymentId) {
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new IllegalStateException("Payment not found: id=" + paymentId));
		if (!payment.isPending()) {
			return payment; // 이미 다른 경로(최초 호출 vs 보정)가 끝냈다 — 중복 반영 방지.
		}

		Wallet wallet = walletRepository.findByIdForUpdate(payment.getWalletId())
				.orElseThrow(() -> new WalletNotFoundException(payment.getWalletId()));
		wallet.pay(payment.getAmount());

		LedgerEntry entry = ledgerEntryRepository.saveAndFlush(
				LedgerEntry.payment(payment.getWalletId(), payment.getMerchantId(), payment.getAmount(),
						wallet.getBalance(), payment.getIdempotencyKey()));

		payment.approve(entry.getId());
		return payment;
	}

	@Transactional
	public Payment markFailed(Long paymentId, String reason) {
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new IllegalStateException("Payment not found: id=" + paymentId));
		if (!payment.isPending()) {
			return payment;
		}
		payment.fail(reason);
		return payment;
	}
}
