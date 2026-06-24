package com.example.wallet.common.exception;

// 환불 누적액이 원결제액을 초과하면 던진다. 이미 전액 환불된 결제에 대한 재환불도 이 예외로
// 막힌다 — 누적액이 이미 결제액과 같으므로 0보다 큰 어떤 추가 환불도 이 조건에 걸린다.
public class RefundExceedsPaymentException extends RuntimeException {

	public RefundExceedsPaymentException(Long paymentId, long alreadyRefunded, long paymentAmount) {
		super("Refund exceeds payment amount: paymentId=" + paymentId
				+ ", alreadyRefunded=" + alreadyRefunded + ", paymentAmount=" + paymentAmount);
	}
}
