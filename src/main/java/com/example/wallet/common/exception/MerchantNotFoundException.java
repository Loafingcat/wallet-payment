package com.example.wallet.common.exception;

public class MerchantNotFoundException extends RuntimeException {

	public MerchantNotFoundException(Long merchantId) {
		super("Merchant not found: id=" + merchantId);
	}
}
