package com.example.wallet.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.config.RabbitConfig;
import com.example.wallet.payment.PaymentCompletedEvent;

import lombok.RequiredArgsConstructor;

// 정산은 S5에서 LedgerEntry를 직접 집계하는 QueryDSL 배치로 이미 정확하게 처리된다.
// 여기서 결제완료 이벤트를 또 누적 집계하면 "진실의 원천이 두 개"가 되어 서로 어긋날
// 위험이 생긴다. 그래서 이 컨슈머는 정산에 손대지 않고 "알림 로깅"만 한다 — 실제로는
// 이 자리에서 SMS/푸시 발송 서비스를 호출했을 자리다.
@Component
@RequiredArgsConstructor
public class PaymentNotificationListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentNotificationListener.class);

	private final ProcessedPaymentEventRepository processedPaymentEventRepository;

	// ProcessedPaymentEvent.paymentId는 @GeneratedValue 없이 직접 할당하는 ID다. Spring Data
	// JPA의 save()는 ID가 null이 아니면 persist() 대신 merge()를 호출하는데, merge()는 먼저
	// SELECT로 존재를 확인하고 없으면 INSERT, 있으면 조용히 UPDATE한다 — 그래서 같은 ID로
	// 두 번 save()해도 두 번째는 제약 위반 예외 없이 그냥 덮어써진다(실제로 처음 구현했을 때
	// 이 함정에 걸려서 중복 메시지가 매번 "처리됨" 로그를 남겼다). existsById()로 먼저 직접
	// 확인하는 방식으로 바꿔서, 중복이면 INSERT/UPDATE 자체를 시도하지 않고 건너뛴다.
	@RabbitListener(queues = RabbitConfig.PAYMENT_COMPLETED_QUEUE)
	@Transactional
	public void onPaymentCompleted(PaymentCompletedEvent event) {
		if (processedPaymentEventRepository.existsById(event.paymentId())) {
			log.info("[알림] 이미 처리한 결제완료 이벤트라 건너뜀: paymentId={}", event.paymentId());
			return;
		}

		processedPaymentEventRepository.save(new ProcessedPaymentEvent(event.paymentId()));

		log.info("[알림] 결제 완료: paymentId={}, walletId={}, merchantId={}, amount={}, balance={}",
				event.paymentId(), event.walletId(), event.merchantId(), event.amount(), event.balance());
	}
}
