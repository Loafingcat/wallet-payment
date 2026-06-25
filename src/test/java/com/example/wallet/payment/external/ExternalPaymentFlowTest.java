package com.example.wallet.payment.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.payment.Payment;
import com.example.wallet.payment.PaymentRepository;
import com.example.wallet.payment.PaymentStatus;
import com.example.wallet.support.IntegrationTestSupport;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

// 4가지 장애 시나리오 모두에서, 결국 우리 원장(LedgerEntry/Wallet.balance)과 PG 상태가
// 일치하는지(또는 안전하게 FAILED로 끝나는지)를 검증한다.
//
// 부모의 클래스 레벨 @Transactional을 NOT_SUPPORTED로 덮어쓴다 — Awaitility의 await()가
// 내부적으로 별도 폴링 스레드에서 조건을 확인하는데, 그 스레드는 테스트 메인 스레드가 들고
// 있는 (아직 commit 안 된) rollback-only 트랜잭션을 볼 수 없다. PaymentConcurrencyTest 등
// 여러 스레드가 끼는 테스트와 같은 이유로, 여기서도 진짜 커밋을 쓰고 @AfterEach에서 직접
// 정리한다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExternalPaymentFlowTest extends IntegrationTestSupport {

	@Autowired
	private ExternalPaymentService externalPaymentService;

	@Autowired
	private PaymentReconciliationService reconciliationService;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private WalletService walletService;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	private Long walletId;

	@AfterEach
	void tearDown() {
		paymentRepository.deleteAll();
		ledgerEntryRepository.deleteAll();
		walletRepository.deleteAll();
	}

	@BeforeEach
	void setUp() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletId = wallet.getId();
		walletService.charge(walletId, 100_000L, newKey());
	}

	@Test
	void 정상_승인이면_즉시_APPROVED되고_원장에_반영된다() {
		Payment payment = externalPaymentService.requestPayment(walletId, 100L, 6_000L, newKey(), null);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(payment.getLedgerEntryId()).isNotNull();
		assertBalanceMatchesLedger(94_000L);
	}

	@Test
	void PG가_5xx로_거부하면_즉시_FAILED되고_원장은_그대로다() {
		Payment payment = externalPaymentService.requestPayment(walletId, 100L, 6_000L, newKey(), "error5xx");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getLedgerEntryId()).isNull();
		assertBalanceMatchesLedger(100_000L); // 변화 없음
	}

	@Test
	void 타임아웃이면_PENDING으로_남고_시간이_지난_뒤_보정하면_APPROVED로_확정된다() {
		Payment payment = externalPaymentService.requestPayment(walletId, 100L, 6_000L, newKey(), "timeout");

		// 클라이언트는 짧은 read-timeout(테스트: 800ms) 안에 포기했다 — 결과를 모른다.
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_PG);

		// 너무 일찍 보정하면(유예 기간 4000ms 안), PG도 아직 처리 전이라 그대로 PENDING이어야
		// 한다 — 성급하게 FAILED로 단정하지 않는다는 게 이 설계의 핵심이다.
		Payment tooEarly = reconciliationService.reconcileOne(payment.getId());
		assertThat(tooEarly.getStatus()).isEqualTo(PaymentStatus.PENDING_PG);

		// fake-pg의 인위적 지연(3000ms)이 끝난 뒤에는 PG가 실제로 승인을 기록해뒀을
		// 것이므로, 보정이 그걸 찾아내 APPROVED로 확정해야 한다. 유예 기간(4000ms)이
		// fake-pg의 지연(3000ms)보다 기므로, 그 사이의 poll들은 NOT_FOUND를 봐도 아직
		// 유예 기간 안이라 PENDING을 유지하다가, 지연이 끝난 시점부터 APPROVED를 본다.
		await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
			Payment reconciled = reconciliationService.reconcileOne(payment.getId());
			assertThat(reconciled.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		});

		Payment result = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(result.getLedgerEntryId()).isNotNull();
		assertBalanceMatchesLedger(94_000L);
	}

	@Test
	void 응답유실이면_PENDING으로_남지만_PG는_이미_승인했으므로_즉시_보정으로_확정된다() {
		Payment payment = externalPaymentService.requestPayment(walletId, 100L, 6_000L, newKey(), "lost-response");

		// 클라이언트는 응답을 못 받아 포기했다 — 우리 쪽 기록은 PENDING.
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_PG);

		// 하지만 PG는 응답을 보내기 "전"에 이미 승인을 기록해뒀으므로, 유예 기간을
		// 기다릴 필요 없이 즉시 보정해도 APPROVED로 확정된다 — timeout 시나리오와의
		// 핵심 차이.
		Payment reconciled = reconciliationService.reconcileOne(payment.getId());

		assertThat(reconciled.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(reconciled.getLedgerEntryId()).isNotNull();
		assertBalanceMatchesLedger(94_000L);
	}

	@Test
	void PG가_끝내_기록을_못찾으면_유예기간_경과_후_FAILED로_확정되고_원장은_그대로다() {
		// PG를 한 번도 호출하지 않은 채로 PENDING_PG 상태만 직접 만든다 — "요청 자체가
		// PG에 끝내 도달하지 못한"(또는 PG가 영원히 처리하지 않는) 가장 비관적인 경우를
		// 재현한다. 이 idempotencyKey로는 PG에 아무 기록도 없다.
		Payment payment = paymentRepository.save(new Payment(walletId, 100L, 6_000L, newKey()));

		await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
			Payment reconciled = reconciliationService.reconcileOne(payment.getId());
			assertThat(reconciled.getStatus()).isEqualTo(PaymentStatus.FAILED);
		});

		assertBalanceMatchesLedger(100_000L); // 변화 없음
	}

	private void assertBalanceMatchesLedger(long expectedBalance) {
		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);
		long ledgerSum = entries.stream()
				.mapToLong(e -> e.getType().name().equals("PAYMENT") ? -e.getAmount() : e.getAmount())
				.sum();

		assertThat(wallet.getBalance()).isEqualTo(expectedBalance);
		assertThat(wallet.getBalance()).isEqualTo(ledgerSum);
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}
}
