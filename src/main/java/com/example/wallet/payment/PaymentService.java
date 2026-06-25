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
import com.example.wallet.outbox.OutboxEvent;
import com.example.wallet.outbox.OutboxEventRepository;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
//
// 이벤트 발행(S10, 아웃박스 패턴): S6에서는 @TransactionalEventListener(AFTER_COMMIT)로
// "커밋 후 발행"했지만, 그 발행 자체가 실패하거나 그 직전에 프로세스가 죽으면 이벤트가
// 영원히 사라지는 문제가 있었다(docs/outbox-pattern.md). 그래서 지금은 "발행해야 할
// 이벤트가 있다"는 사실 자체를 OutboxEvent로 만들어 LedgerEntry와 같은 트랜잭션 안에서
// INSERT한다 — 이러면 이 트랜잭션이 커밋되는 순간 이벤트도 항상 같이 살아남는다. 실제
// RabbitMQ 발행은 OutboxRelay가 별도로, 트랜잭션 밖에서 한다.
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	@Transactional
	public PaymentResponse pay(Long walletId, Long merchantId, long amount, String idempotencyKey) {
		Optional<LedgerEntry> existing = ledgerEntryRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return toPaymentResponse(existing.get(), idempotencyKey);
		}

		Wallet wallet = walletRepository.findByIdForUpdate(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));

		wallet.pay(amount);

		LedgerEntry entry;
		try {
			entry = ledgerEntryRepository.saveAndFlush(
					LedgerEntry.payment(walletId, merchantId, amount, wallet.getBalance(), idempotencyKey));
		} catch (DataIntegrityViolationException e) {
			throw new DuplicateIdempotencyKeyException(idempotencyKey, e);
		}

		outboxEventRepository.save(OutboxEvent.of("PaymentCompleted",
				serialize(new PaymentCompletedEvent(entry.getId(), walletId, merchantId, amount, wallet.getBalance()))));

		return new PaymentResponse(entry.getId(), walletId, merchantId, wallet.getBalance());
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
		return new PaymentResponse(entry.getId(), entry.getWalletId(), entry.getMerchantId(), entry.getBalanceAfter());
	}

	private String serialize(PaymentCompletedEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize outbox payload", e);
		}
	}
}
