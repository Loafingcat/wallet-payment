package com.example.wallet.payment;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.wallet.config.RabbitConfig;

import lombok.RequiredArgsConstructor;

// 왜 커밋 "전"에 발행하면 안 되는가:
// PaymentService.pay()는 결제 처리 도중 멱등키 충돌 등으로 트랜잭션이 롤백될 수 있다.
// 만약 결제 로직 안에서 RabbitMQ로 바로 메시지를 보내버리면, 그 직후 같은 트랜잭션이
// 롤백돼도 메시지는 이미 나간 뒤라 주워올 수 없다 — DB에는 존재하지 않는 결제에 대해
// 컨슈머가 "결제 완료" 처리를 하게 된다(정산에 잘못 반영되거나, 없는 결제로 알림이 감).
// @TransactionalEventListener(AFTER_COMMIT)는 이 리스너를 트랜잭션이 실제로 commit된
// "후"에만 실행해서, DB에 확정된 결제에 대해서만 메시지가 나가도록 보장한다.
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

	private final RabbitTemplate rabbitTemplate;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(PaymentCompletedEvent event) {
		rabbitTemplate.convertAndSend(RabbitConfig.PAYMENT_COMPLETED_QUEUE, event);
	}
}
