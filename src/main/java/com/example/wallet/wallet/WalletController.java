package com.example.wallet.wallet;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {

	private final WalletService walletService;

	@PostMapping("/{id}/charge")
	public ResponseEntity<ChargeResponse> charge(@PathVariable Long id, @Valid @RequestBody ChargeRequest request) {
		ChargeResponse response = walletService.charge(id, request.amount());
		return ResponseEntity.ok(response);
	}
}
