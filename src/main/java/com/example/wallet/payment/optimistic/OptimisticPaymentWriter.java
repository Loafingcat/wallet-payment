package com.example.wallet.payment.optimistic;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.outbox.OutboxEvent;
import com.example.wallet.outbox.OutboxEventRepository;
import com.example.wallet.payment.PaymentCompletedEvent;
import com.example.wallet.payment.PaymentResponse;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

// PaymentService(비관적 락)와 대조되는 낙관적 락 버전. findByIdForUpdate가 아니라 평범한
// findById를 쓴다 — 행 잠금이 전혀 없다. 대신 Wallet에 이미 있는 @Version이 막아준다:
// 두 트랜잭션이 같은 버전을 보고 동시에 commit을 시도하면, 늦게 flush되는 쪽의
// UPDATE ... WHERE version=? 이 0행에 매치되어 ObjectOptimisticLockingFailureException이
// 터진다. 이 클래스는 "한 번 시도"만 책임지고, 실패 시 재시도는 OptimisticPaymentService가
// (다른 빈을 통해 호출해서 매번 새 트랜잭션으로) 한다.
@Service
@RequiredArgsConstructor
public class OptimisticPaymentWriter {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	@Transactional
	public PaymentResponse payOnce(Long walletId, Long merchantId, long amount, String idempotencyKey) {
		Optional<LedgerEntry> existing = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			LedgerEntry entry = existing.get();
			return new PaymentResponse(entry.getId(), entry.getWalletId(), entry.getMerchantId(),
					entry.getBalanceAfter());
		}

		// 락 없이 읽는다 — 이 시점의 balance는 다른 트랜잭션이 동시에 commit하면 곧바로 낡은
		// 값이 될 수 있다. 그래서 아래 wallet.pay()의 검증도 "이번 시도에서는" 맞다고 믿을
		// 뿐, commit 시점까지 보장되지는 않는다.
		Wallet wallet = walletRepository.findById(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));

		wallet.pay(amount);

		LedgerEntry entry = ledgerEntryRepository.saveAndFlush(
				LedgerEntry.payment(walletId, merchantId, amount, wallet.getBalance(), idempotencyKey));

		// PaymentService와 동일하게 아웃박스에 적재한다(S10) — 부하 테스트(S8)에서 두 경로의
		// 비교가 "락 전략"만의 차이를 보여주도록, 메시징 오버헤드도 양쪽에 똑같이 둔다.
		outboxEventRepository.save(OutboxEvent.of("PaymentCompleted",
				serialize(new PaymentCompletedEvent(entry.getId(), walletId, merchantId, amount, wallet.getBalance()))));

		return new PaymentResponse(entry.getId(), walletId, merchantId, wallet.getBalance());
	}

	private String serialize(PaymentCompletedEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize outbox payload", e);
		}
	}
}
