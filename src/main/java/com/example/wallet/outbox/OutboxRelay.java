package com.example.wallet.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.example.wallet.config.RabbitConfig;
import com.example.wallet.payment.PaymentCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

// 비즈니스 트랜잭션과 같이 커밋된 PENDING 행을 읽어서 RabbitMQ로 보내고, 보내는 데 성공한
// 것만 PUBLISHED로 표시한다. 발행(외부 호출)은 절대 규칙 6번과 같은 이유로 트랜잭션 안에
// 두지 않는다 — outboxEventRepository.save()는 그 자체로 독립된 트랜잭션이라(Spring Data
// 리포지토리 메서드 자체가 @Transactional이다), relayPending()/relayOne()을 이 클래스에서
// 직접 또 @Transactional로 감쌀 필요가 없다(그랬다면 또 self-invocation 문제가 났을 것).
@Component
@RequiredArgsConstructor
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxEventRepository outboxEventRepository;
	private final RabbitTemplate rabbitTemplate;
	private final ObjectMapper objectMapper;

	public void relayPending() {
		List<OutboxEvent> pending = outboxEventRepository.findByStatusOrderByIdAsc(OutboxStatus.PENDING);
		for (OutboxEvent event : pending) {
			relayOne(event);
		}
	}

	private void relayOne(OutboxEvent event) {
		try {
			Object message = deserialize(event);
			rabbitTemplate.convertAndSend(resolveQueue(event.getEventType()), message);
			event.markPublished();
			outboxEventRepository.save(event);
		} catch (Exception e) {
			// 여기서 막혀도 이 행은 PENDING으로 남는다 — 다음 폴링에서 다시 시도된다.
			// 한 건이 실패해도(예: RabbitMQ 일시 장애) 나머지 PENDING 행 처리는 계속된다.
			log.warn("[Outbox] 발행 실패, 다음 폴링에서 재시도: id={}, eventType={}, cause={}",
					event.getId(), event.getEventType(), e.getMessage());
		}
	}

	private Object deserialize(OutboxEvent event) throws Exception {
		return switch (event.getEventType()) {
			case "PaymentCompleted" -> objectMapper.readValue(event.getPayload(), PaymentCompletedEvent.class);
			default -> throw new IllegalStateException("Unknown outbox event type: " + event.getEventType());
		};
	}

	private String resolveQueue(String eventType) {
		return switch (eventType) {
			case "PaymentCompleted" -> RabbitConfig.PAYMENT_COMPLETED_QUEUE;
			default -> throw new IllegalStateException("Unknown outbox event type: " + eventType);
		};
	}
}
