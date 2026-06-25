package com.example.fakepg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// X-Simulate-Failure 헤더로 장애를 주입한다(none(기본)/timeout/error5xx/lost-response).
// 진짜 PG라면 우리가 장애를 "주문"할 수 없겠지만, 시뮬레이터니까 테스트가 원하는 장애를
// 결정적으로 재현할 수 있게 해뒀다.
@RestController
@RequestMapping("/pg")
public class PgApproveController {

	private static final Logger log = LoggerFactory.getLogger(PgApproveController.class);

	private final PgApprovalStore store;

	public PgApproveController(PgApprovalStore store) {
		this.store = store;
	}

	@PostMapping("/approve")
	public ResponseEntity<?> approve(
			@RequestHeader("PG-Idempotency-Key") String idempotencyKey,
			@RequestHeader(value = "X-Simulate-Failure", required = false, defaultValue = "none") String simulate,
			@RequestBody PgApproveRequest request) throws InterruptedException {

		// PG도 멱등키를 지원한다 — 같은 키로 또 오면 재처리하지 않고 같은 결과를 돌려준다.
		PgApprovalStore.PgApproval existing = store.find(idempotencyKey);
		if (existing != null) {
			log.info("[PG] 이미 처리한 키, 같은 결과 반환: {}", idempotencyKey);
			return ResponseEntity.ok(new PgApproveResponse("APPROVED", idempotencyKey));
		}

		switch (simulate) {
			case "error5xx":
				// 승인을 기록하지 않고 바로 명확하게 실패시킨다 — 이 시뮬레이션 안에서는
				// "5xx == 확실히 처리 안 됨"으로 정의한다(실제 PG는 이보다 모호할 수 있다).
				log.info("[PG] 5xx 시뮬레이션(승인 기록 없음): {}", idempotencyKey);
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.body(new PgErrorResponse("PG_INTERNAL_ERROR"));

			case "timeout":
				// 클라이언트의 타임아웃(짧게 설정)보다 길게 슬립한 "후"에 승인을 기록한다.
				// 클라이언트가 포기한 시점엔 PG도 아직 처리 전이었다는 뜻 — 지금 당장
				// 조회해도 못 찾고, 조금 있다가 조회하면 그제서야 승인되어 있다.
				Thread.sleep(3000);
				store.record(idempotencyKey, request.amount());
				log.info("[PG] 지연 후 승인: {}", idempotencyKey);
				return ResponseEntity.ok(new PgApproveResponse("APPROVED", idempotencyKey));

			case "lost-response":
				// 클라이언트가 포기하기 "전"에 이미 승인을 기록해둔다 — 처리는 끝났는데
				// 응답만 못 받은 가장 까다로운 상황. 지금 당장 조회해도 이미 승인 상태다.
				store.record(idempotencyKey, request.amount());
				Thread.sleep(3000);
				log.info("[PG] 응답유실 시뮬레이션(승인은 이미 기록됨): {}", idempotencyKey);
				return ResponseEntity.ok(new PgApproveResponse("APPROVED", idempotencyKey));

			default:
				store.record(idempotencyKey, request.amount());
				log.info("[PG] 정상 승인: {}", idempotencyKey);
				return ResponseEntity.ok(new PgApproveResponse("APPROVED", idempotencyKey));
		}
	}

	// 보정(reconciliation)이 "이 키, 실제로 어떻게 됐어?"를 물어보는 자리.
	@GetMapping("/payments/{idempotencyKey}")
	public ResponseEntity<PgApproveResponse> status(@PathVariable String idempotencyKey) {
		PgApprovalStore.PgApproval approval = store.find(idempotencyKey);
		if (approval == null) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(new PgApproveResponse("APPROVED", idempotencyKey));
	}
}
