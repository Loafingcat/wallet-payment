package com.example.wallet.reconciliation;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

// 테스트/시연용 수동 트리거. 실제 배치는 BalanceReconciliationScheduler가 매일 새벽 자동으로 돈다.
@RestController
@RequestMapping("/reconciliation")
@RequiredArgsConstructor
public class BalanceReconciliationController {

	private final BalanceReconciliationService balanceReconciliationService;

	@PostMapping("/run")
	public ResponseEntity<List<BalanceDiscrepancyResponse>> run() {
		List<BalanceDiscrepancyResponse> response = balanceReconciliationService.reconcile().stream()
				.map(BalanceDiscrepancyResponse::from)
				.toList();
		return ResponseEntity.ok(response);
	}
}
