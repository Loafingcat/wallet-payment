package com.example.wallet.common.exception;

public class InsufficientBalanceException extends RuntimeException {

	public InsufficientBalanceException(Long walletId) {
		super("Insufficient balance: walletId=" + walletId);
	}
}
