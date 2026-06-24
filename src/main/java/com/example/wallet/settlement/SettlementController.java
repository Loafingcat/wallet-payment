package com.example.wallet.settlement;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

// 테스트/시연용 수동 트리거. 실제 배치는 SettlementScheduler가 매일 새벽 자동으로 돈다.
@RestController
@RequestMapping("/settlements")
@RequiredArgsConstructor
public class SettlementController {

	private final SettlementBatchRunner settlementBatchRunner;

	@PostMapping("/run")
	public ResponseEntity<List<SettlementResponse>> run(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) Long merchantId) {
		List<Settlement> settlements = settlementBatchRunner.run(date, merchantId);
		List<SettlementResponse> response = settlements.stream().map(SettlementResponse::from).toList();
		return ResponseEntity.ok(response);
	}
}
