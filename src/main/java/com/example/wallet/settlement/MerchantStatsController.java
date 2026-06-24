package com.example.wallet.settlement;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MerchantStatsController {

	private final MerchantStatsService merchantStatsService;

	@GetMapping("/merchants/{id}/stats")
	public ResponseEntity<MerchantStatsResponse> statsForMerchant(
			@PathVariable Long id,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok(merchantStatsService.statsFor(id, from, to));
	}

	@GetMapping("/merchants/stats")
	public ResponseEntity<List<MerchantStatsResponse>> statsForAllMerchants(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ResponseEntity.ok(merchantStatsService.statsForAllMerchants(from, to));
	}
}
