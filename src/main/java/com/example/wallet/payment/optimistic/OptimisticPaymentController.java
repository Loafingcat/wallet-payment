package com.example.wallet.payment.optimistic;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet.payment.PaymentRequest;
import com.example.wallet.payment.PaymentResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 비관적 락 버전(PaymentController, POST /payments)과 비교하기 위한 낙관적 락 엔드포인트.
// S8 부하 테스트가 이 엔드포인트와 /payments를 각각 두드려서 같은 시나리오를 비교한다.
// X-Attempt-Count 응답 헤더로 "이 요청이 성공/실패까지 몇 번 시도했는지"를 그대로 내보내서,
// 부하 테스트 도구(k6)가 재시도율을 클라이언트 쪽에서 직접 집계할 수 있게 한다.
@RestController
@RequestMapping("/payments/optimistic")
@RequiredArgsConstructor
public class OptimisticPaymentController {

	private final OptimisticPaymentService optimisticPaymentService;

	@PostMapping
	public ResponseEntity<PaymentResponse> pay(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody PaymentRequest request) {
		OptimisticPaymentResult result = optimisticPaymentService.pay(
				request.walletId(), request.merchantId(), request.amount(), idempotencyKey);
		return ResponseEntity.ok()
				.header("X-Attempt-Count", String.valueOf(result.attempts()))
				.body(result.response());
	}
}
