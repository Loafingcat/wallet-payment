package com.example.wallet.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

	private final PaymentService paymentService;

	@PostMapping
	public ResponseEntity<PaymentResponse> pay(@Valid @RequestBody PaymentRequest request) {
		PaymentResponse response = paymentService.pay(request.walletId(), request.merchantId(), request.amount());
		return ResponseEntity.ok(response);
	}
}
