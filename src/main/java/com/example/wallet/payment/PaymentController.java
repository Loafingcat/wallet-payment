package com.example.wallet.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet.common.exception.DuplicateIdempotencyKeyException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

	private final PaymentService paymentService;

	@PostMapping
	public ResponseEntity<PaymentResponse> pay(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody PaymentRequest request) {
		PaymentResponse response;
		try {
			response = paymentService.pay(request.walletId(), request.merchantId(), request.amount(), idempotencyKey);
		} catch (DuplicateIdempotencyKeyException e) {
			response = paymentService.findPaymentResultByIdempotencyKey(idempotencyKey);
		}
		return ResponseEntity.ok(response);
	}
}
