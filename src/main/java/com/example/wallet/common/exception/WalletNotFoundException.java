package com.example.wallet.common.exception;

public class WalletNotFoundException extends RuntimeException {

	public WalletNotFoundException(Long walletId) {
		super("Wallet not found: id=" + walletId);
	}
}
