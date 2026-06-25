package com.example.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.config.RabbitConfig;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.notification.ProcessedPaymentEventRepository;
import com.example.wallet.payment.PaymentCompletedEvent;
import com.example.wallet.payment.PaymentResponse;
import com.example.wallet.payment.PaymentService;
import com.example.wallet.support.IntegrationTestSupport;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

// PaymentConcurrencyTest와 같은 이유로 NOT_SUPPORTED — 릴레이가 보내는 RabbitMQ 메시지를
// 소비자(별도 스레드)가 받아서 처리하므로, 그 소비자가 우리 테스트 트랜잭션의 커밋된
// 결과를 봐야 한다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRelayTest extends IntegrationTestSupport {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private WalletService walletService;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private ProcessedPaymentEventRepository processedPaymentEventRepository;

	@AfterEach
	void tearDown() {
		outboxEventRepository.deleteAll();
		processedPaymentEventRepository.deleteAll();
		ledgerEntryRepository.deleteAll();
		walletRepository.deleteAll();
	}

	@Test
	void 결제_트랜잭션이_커밋되면_발행_전이라도_outbox에_안전하게_남는다() {
		Long walletId = setUpWallet();

		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());

		// "릴레이가 아직 한 번도 안 돈 상태"는 "발행 직전에 프로세스가 죽은 상태"와
		// 우리 입장에서 구분할 수 없다 — 둘 다 outbox에 PENDING 행이 남아있을 뿐이다.
		// 중요한 건 비즈니스 데이터(지갑 차감, LedgerEntry)는 이미 확정됐다는 것.
		List<OutboxEvent> events = outboxEventRepository.findAll();
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);

		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		assertThat(wallet.getBalance()).isEqualTo(94_000L);
		assertThat(payment.paymentId()).isNotNull();
	}

	@Test
	void 재구동을_시뮬레이션한_릴레이_실행은_유실_없이_PENDING_이벤트를_발행한다() {
		Long walletId = setUpWallet();
		paymentService.pay(walletId, 100L, 6_000L, newKey());

		// "재구동 후 릴레이가 다시 돈다"를 그대로 흉내낸다 — 새로 생성된 OutboxRelay
		// 인스턴스가 아니라 같은 빈이지만, 핵심은 "이전에 무슨 일이 있었는지 몰라도
		// PENDING이라는 사실만 보고 처리한다"는 점이다(상태가 DB에 있어서 가능하다).
		outboxRelay.relayPending();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			OutboxEvent event = outboxEventRepository.findAll().get(0);
			assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
			assertThat(event.getPublishedAt()).isNotNull();
		});
	}

	@Test
	void 릴레이가_발행한_메시지를_소비자가_정상_처리한다() {
		Long walletId = setUpWallet();
		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());

		outboxRelay.relayPending();

		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(
						processedPaymentEventRepository.existsById(payment.paymentId())).isTrue());
	}

	@Test
	void 알수없는_이벤트_타입이_섞여도_나머지_PENDING_이벤트_발행은_계속된다() {
		Long walletId = setUpWallet();
		PaymentResponse payment = paymentService.pay(walletId, 100L, 6_000L, newKey());

		OutboxEvent broken = outboxEventRepository.save(OutboxEvent.of("UnknownEventType", "{}"));

		outboxRelay.relayPending();

		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(
						processedPaymentEventRepository.existsById(payment.paymentId())).isTrue());

		OutboxEvent brokenAfter = outboxEventRepository.findById(broken.getId()).orElseThrow();
		assertThat(brokenAfter.getStatus()).isEqualTo(OutboxStatus.PENDING); // 계속 재시도 대상으로 남음
	}

	@Test
	void 같은_이벤트가_중복_발행돼도_소비자_멱등성이_중복_처리를_막는다() {
		// "릴레이가 RabbitMQ 발행엔 성공했지만 PUBLISHED로 표시하기 직전에 죽어서, 다음
		// 폴링이 같은 행을 또 발행하는" 상황을 재현한다. 아웃박스는 at-least-once만
		// 보장하므로 이런 중복은 원래부터 있을 수 있는 일이다 — 그래서 소비자(S6의
		// ProcessedPaymentEvent 멱등성)가 막아줘야 한다.
		Long paymentId = 999_001L;
		PaymentCompletedEvent event = new PaymentCompletedEvent(paymentId, 1L, 1L, 1_000L, 9_000L);

		rabbitTemplate.convertAndSend(RabbitConfig.PAYMENT_COMPLETED_QUEUE, event);
		rabbitTemplate.convertAndSend(RabbitConfig.PAYMENT_COMPLETED_QUEUE, event);

		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(processedPaymentEventRepository.existsById(paymentId)).isTrue());

		var firstProcessedAt = processedPaymentEventRepository.findById(paymentId).orElseThrow().getProcessedAt();

		// 두 번째 메시지까지 처리될 시간을 더 준 뒤에도, paymentId는 PK라 행이 두 개일
		// 수는 없다 — 대신 processedAt이 그대로인지로 "두 번째가 다시 처리(갱신)되지
		// 않았다"를 확인한다(PaymentEventPublisherTest와 같은 검증 방식).
		await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> assertThat(processedPaymentEventRepository.findById(paymentId).orElseThrow()
						.getProcessedAt()).isEqualTo(firstProcessedAt));
	}

	private Long setUpWallet() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletService.charge(wallet.getId(), 100_000L, newKey());
		return wallet.getId();
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}
}
