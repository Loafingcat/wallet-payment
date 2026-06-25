package com.example.wallet.outbox;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

// 테스트/시연용 수동 트리거. 실제로는 OutboxRelayScheduler가 2초마다 돈다.
@RestController
@RequestMapping("/outbox")
@RequiredArgsConstructor
public class OutboxController {

	private final OutboxRelay outboxRelay;

	@PostMapping("/relay")
	public ResponseEntity<Void> relay() {
		outboxRelay.relayPending();
		return ResponseEntity.ok().build();
	}
}
