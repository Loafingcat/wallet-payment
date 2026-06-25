package com.example.fakepg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

// 진짜 PG라면 자기 DB에 거래를 기록하겠지만, 이건 시뮬레이터라 인메모리로 충분하다.
// 여기 들어있는 게 "PG 쪽의 진실"이다 — 우리 wallet-payment 앱은 이걸 직접 들여다볼 수
// 없고, HTTP로 물어보는 것(GET /pg/payments/{key})만 할 수 있다. 이 비대칭이 S9가
// 재현하려는 "분산 정합성 문제"의 핵심이다.
@Component
public class PgApprovalStore {

	private final Map<String, PgApproval> approvals = new ConcurrentHashMap<>();

	public void record(String idempotencyKey, long amount) {
		approvals.putIfAbsent(idempotencyKey, new PgApproval(idempotencyKey, amount));
	}

	public PgApproval find(String idempotencyKey) {
		return approvals.get(idempotencyKey);
	}

	public record PgApproval(String idempotencyKey, long amount) {
	}
}
