package com.example.wallet.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.wallet.config.RabbitConfig;
import com.example.wallet.notification.PaymentNotificationListener;
import com.example.wallet.notification.ProcessedPaymentEventRepository;
import com.example.wallet.support.IntegrationTestSupport;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// 부모의 클래스 레벨 @Transactional을 NOT_SUPPORTED로 끈다 — 그게 켜져 있으면 테스트 메서드
// 전체가 하나의 (절대 commit되지 않는) 트랜잭션이 되어서, "진짜 commit되면 이벤트가 나간다"는
// 시나리오 자체를 만들 수 없다(PaymentConcurrencyTest와 같은 이유).
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentEventPublisherTest extends IntegrationTestSupport {

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private ProcessedPaymentEventRepository processedPaymentEventRepository;

	@AfterEach
	void tearDown() {
		processedPaymentEventRepository.deleteAll();
	}

	@Test
	void 트랜잭션이_롤백되면_이벤트가_발행되지_않는다() {
		Long paymentId = uniquePaymentId();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.execute(status -> {
			eventPublisher.publishEvent(samplePaymentCompletedEvent(paymentId));
			status.setRollbackOnly(); // 결제는 끝까지 못 가고 이 시점에 실패했다고 가정
			return null;
		});

		// 롤백됐으니 컨슈머가 메시지를 받을 일이 없다 — 충분히 기다려도 안 생겨야 한다.
		await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> assertThat(processedPaymentEventRepository.existsById(paymentId)).isFalse());
	}

	@Test
	void 트랜잭션이_커밋되어야_이벤트가_발행되고_컨슈머가_처리한다() {
		Long paymentId = uniquePaymentId();
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.execute(status -> {
			eventPublisher.publishEvent(samplePaymentCompletedEvent(paymentId));
			return null;
		});

		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(processedPaymentEventRepository.existsById(paymentId)).isTrue());
	}

	@Test
	void 같은_결제완료_메시지가_두_번_와도_한_번만_처리된다() {
		Long paymentId = uniquePaymentId();
		PaymentCompletedEvent event = samplePaymentCompletedEvent(paymentId);

		// 다른 테스트(클래스)도 같은 큐/테이블을 공유해서 동시에 메시지를 처리하므로, 행 개수
		// count()로 "전체가 1개"를 검증할 수 없다. 그래서 리스너 로거에 ListAppender를 직접
		// 붙여서, 이 paymentId에 대한 "건너뜀" 로그가 정확히 한 번 찍히는지로 검증한다.
		Logger listenerLogger = (Logger) LoggerFactory.getLogger(PaymentNotificationListener.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		listenerLogger.addAppender(appender);

		try {
			rabbitTemplate.convertAndSend(RabbitConfig.PAYMENT_COMPLETED_QUEUE, event);
			rabbitTemplate.convertAndSend(RabbitConfig.PAYMENT_COMPLETED_QUEUE, event);

			await().atMost(Duration.ofSeconds(5))
					.untilAsserted(() -> assertThat(processedPaymentEventRepository.existsById(paymentId)).isTrue());

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				long skippedLogCount = countLogsContaining(appender, "건너뜀", paymentId.toString());
				assertThat(skippedLogCount).isEqualTo(1);
			});

			long processedLogCount = countLogsContaining(appender, "결제 완료", paymentId.toString());
			assertThat(processedLogCount).isEqualTo(1);
		} finally {
			listenerLogger.detachAppender(appender);
		}
	}

	private long countLogsContaining(ListAppender<ILoggingEvent> appender, String... mustContainAll) {
		List<ILoggingEvent> events = appender.list;
		return events.stream()
				.filter(e -> {
					String message = e.getFormattedMessage();
					for (String token : mustContainAll) {
						if (!message.contains(token)) {
							return false;
						}
					}
					return true;
				})
				.count();
	}

	private PaymentCompletedEvent samplePaymentCompletedEvent(Long paymentId) {
		return new PaymentCompletedEvent(paymentId, 1L, 1L, 1_000L, 9_000L);
	}

	private Long uniquePaymentId() {
		return Math.abs(UUID.randomUUID().getMostSignificantBits());
	}
}
