package com.example.wallet.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.wallet.common.exception.RefundExceedsPaymentException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.ledger.LedgerType;
import com.example.wallet.support.IntegrationTestSupport;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

class RefundServiceTest extends IntegrationTestSupport {

	@Autowired
	private RefundService refundService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private WalletService walletService;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	private Long walletId;

	@BeforeEach
	void setUp() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletId = wallet.getId();
		walletService.charge(walletId, 10_000L, newKey());
	}

	@Test
	void 전액_환불하면_잔액이_결제_전으로_돌아온다() {
		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());

		RefundResponse refund = refundService.refund(payment.paymentId(), 6_000L, newKey());

		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		assertThat(wallet.getBalance()).isEqualTo(10_000L);
		assertThat(refund.totalRefunded()).isEqualTo(6_000L);
	}

	@Test
	void 부분_환불_두_번이_한도_내면_모두_성공한다() {
		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());

		refundService.refund(payment.paymentId(), 3_000L, newKey());
		RefundResponse second = refundService.refund(payment.paymentId(), 3_000L, newKey());

		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		assertThat(wallet.getBalance()).isEqualTo(10_000L);
		assertThat(second.totalRefunded()).isEqualTo(6_000L);
	}

	@Test
	void 부분_환불_2회_후_합이_원결제액을_초과하면_거부되고_잔액과_원장은_그대로_유지된다() {
		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());
		refundService.refund(payment.paymentId(), 4_000L, newKey());

		assertThatThrownBy(() -> refundService.refund(payment.paymentId(), 3_000L, newKey()))
				.isInstanceOf(RefundExceedsPaymentException.class);

		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);
		long refundedSum = entries.stream()
				.filter(e -> e.getType() == LedgerType.REFUND)
				.mapToLong(LedgerEntry::getAmount)
				.sum();

		// 거부된 두 번째 환불의 효과(지갑 락 안에서 증가 시도했던 것)는 트랜잭션 롤백으로
		// 전혀 반영되지 않아야 한다 — 잔액은 첫 환불(4000)까지만 반영된 8000.
		assertThat(wallet.getBalance()).isEqualTo(8_000L);
		assertThat(refundedSum).isEqualTo(4_000L);
		assertThat(entries).hasSize(3); // setUp의 CHARGE 1건 + PAYMENT 1건 + REFUND 1건(거부된 두 번째는 롤백되어 안 남음)
	}

	@Test
	void 이미_전액_환불된_결제는_재환불이_거부된다() {
		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());
		refundService.refund(payment.paymentId(), 6_000L, newKey());

		assertThatThrownBy(() -> refundService.refund(payment.paymentId(), 1L, newKey()))
				.isInstanceOf(RefundExceedsPaymentException.class);
	}

	@Test
	void 같은_키로_두_번_환불해도_차감은_한_번만_일어난다() {
		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());
		String idempotencyKey = newKey();

		RefundResponse first = refundService.refund(payment.paymentId(), 4_000L, idempotencyKey);
		RefundResponse second = refundService.refund(payment.paymentId(), 4_000L, idempotencyKey);

		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		assertThat(second).isEqualTo(first);
		assertThat(wallet.getBalance()).isEqualTo(8_000L); // 4000만 반영, 두 번째 호출은 캐시된 결과 반환
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}
}
