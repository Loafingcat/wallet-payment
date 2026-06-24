package com.example.wallet.common.exception;

import java.time.LocalDate;

public class DuplicateSettlementException extends RuntimeException {

	public DuplicateSettlementException(Long merchantId, LocalDate settlementDate, Throwable cause) {
		super("Duplicate settlement: merchantId=" + merchantId + ", date=" + settlementDate, cause);
	}
}
