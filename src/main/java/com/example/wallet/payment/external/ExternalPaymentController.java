package com.example.wallet.payment.external;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet.common.exception.PaymentNotFoundException;
import com.example.wallet.payment.Payment;
import com.example.wallet.payment.PaymentRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// S2의 비관적 락 결제(POST /payments)와 별개의 새 경로다. 기존 경로는 "우리 DB만 바꾸면
// 끝나는 결제"를 계속 대표하고, 여기는 "외부 PG 승인이 끼는 결제"를 대표한다 — 기존
// 동작을 안 건드리고 새로 추가했다.
@RestController
@RequestMapping("/payments/external")
@RequiredArgsConstructor
public class ExternalPaymentController {

	private final ExternalPaymentService externalPaymentService;
	private final PaymentReconciliationService reconciliationService;
	private final PaymentRepository paymentRepository;

	@PostMapping
	public ResponseEntity<ExternalPaymentResponse> pay(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestHeader(value = "X-Simulate-Failure", required = false) String simulateFailure,
			@Valid @RequestBody ExternalPaymentRequest request) {
		Payment payment = externalPaymentService.requestPayment(
				request.walletId(), request.merchantId(), request.amount(), idempotencyKey, simulateFailure);
		return ResponseEntity.ok(ExternalPaymentResponse.from(payment));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ExternalPaymentResponse> get(@PathVariable Long id) {
		Payment payment = paymentRepository.findById(id)
				.orElseThrow(() -> new PaymentNotFoundException(id));
		return ResponseEntity.ok(ExternalPaymentResponse.from(payment));
	}

	// 테스트/시연용 수동 보정 트리거. 실제로는 PaymentReconciliationScheduler가 주기적으로 돈다.
	@PostMapping("/{id}/reconcile")
	public ResponseEntity<ExternalPaymentResponse> reconcile(@PathVariable Long id) {
		Payment payment = reconciliationService.reconcileOne(id);
		return ResponseEntity.ok(ExternalPaymentResponse.from(payment));
	}
}
