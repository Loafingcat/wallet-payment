package com.example.wallet.common.exception;

public class PaymentNotFoundException extends RuntimeException {

	public PaymentNotFoundException(Long paymentId) {
		super("Payment not found: id=" + paymentId);
	}
}
