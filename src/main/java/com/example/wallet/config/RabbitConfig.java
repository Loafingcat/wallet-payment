package com.example.wallet.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

	public static final String PAYMENT_COMPLETED_QUEUE = "payment.completed.queue";

	// 별도 익스체인지 없이 default exchange("")에 큐 이름을 라우팅 키로 써서 보낸다.
	// 이벤트 종류가 하나뿐이라 지금은 익스체인지/라우팅 규칙을 따로 설계할 이유가 없다.
	@Bean
	public Queue paymentCompletedQueue() {
		return new Queue(PAYMENT_COMPLETED_QUEUE, true);
	}

	// 기본 컨버터는 자바 직렬화를 쓴다 — 사람이 못 읽고, 컨슈머가 다른 언어/버전이면 깨진다.
	// JSON으로 바꿔서 메시지를 그대로 들여다볼 수 있게 한다(관리 UI에서도 확인 가능).
	@Bean
	public MessageConverter jsonMessageConverter() {
		return new Jackson2JsonMessageConverter();
	}
}
