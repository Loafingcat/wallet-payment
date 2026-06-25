package com.example.wallet.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

	private final OutboxRelay outboxRelay;

	// 2초마다 — 너무 길면 이벤트가 늦게 나가고, 너무 짧으면 DB를 의미 없이 자주 긁는다.
	@Scheduled(fixedDelay = 2_000)
	public void relay() {
		outboxRelay.relayPending();
	}
}
