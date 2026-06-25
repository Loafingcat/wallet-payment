package com.example.wallet.outbox;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// S6의 @TransactionalEventListener(AFTER_COMMIT) 방식은 "DB 커밋"과 "RabbitMQ 발행"이
// 여전히 별개의 단계였다 — 커밋은 됐는데 발행 직전에 프로세스가 죽으면 이벤트가 영원히
// 사라진다(어디에도 "발행해야 한다"는 기록이 안 남으므로). 아웃박스 패턴은 "발행할 이벤트가
// 있다"는 사실 자체를 비즈니스 데이터(지갑 차감, LedgerEntry)와 같은 로컬 트랜잭션 안에
// 영속화한다. 그래서 비즈니스 변경이 커밋되면 이 행도 항상 같이 커밋되어 있다 — 발행은
// 그 다음에 별도 릴레이가 책임지고, 릴레이가 죽어도 이 행은 PENDING으로 남아있어서
// 재시작하면 다시 시도된다. docs/outbox-pattern.md에 두 방식의 비교를 정리해뒀다.
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String eventType;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OutboxStatus status;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column
	private LocalDateTime publishedAt;

	private OutboxEvent(String eventType, String payload) {
		this.eventType = eventType;
		this.payload = payload;
		this.status = OutboxStatus.PENDING;
	}

	public static OutboxEvent of(String eventType, String payload) {
		return new OutboxEvent(eventType, payload);
	}

	public void markPublished() {
		this.status = OutboxStatus.PUBLISHED;
		this.publishedAt = LocalDateTime.now();
	}
}
