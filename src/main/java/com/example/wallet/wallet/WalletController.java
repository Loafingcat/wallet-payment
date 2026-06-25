package com.example.wallet.wallet;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet.common.exception.DuplicateIdempotencyKeyException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {

	private final WalletService walletService;
	private final TransferService transferService;

	@PostMapping("/{id}/charge")
	public ResponseEntity<ChargeResponse> charge(
			@PathVariable Long id,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody ChargeRequest request) {
		ChargeResponse response;
		try {
			response = walletService.charge(id, request.amount(), idempotencyKey);
		} catch (DuplicateIdempotencyKeyException e) {
			response = walletService.findChargeResultByIdempotencyKey(idempotencyKey);
		}
		return ResponseEntity.ok(response);
	}

	@PostMapping("/transfer")
	public ResponseEntity<TransferResponse> transfer(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody TransferRequest request) {
		TransferResponse response;
		try {
			response = transferService.transfer(request.fromWalletId(), request.toWalletId(), request.amount(),
					idempotencyKey);
		} catch (DuplicateIdempotencyKeyException e) {
			response = transferService.findTransferResultByIdempotencyKey(request.fromWalletId(),
					request.toWalletId(), request.amount(), idempotencyKey);
		}
		return ResponseEntity.ok(response);
	}
}
