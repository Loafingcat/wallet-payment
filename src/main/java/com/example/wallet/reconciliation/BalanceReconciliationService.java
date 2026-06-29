package com.example.wallet.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 절대 규칙 2번(원장이 진실의 원천)을 "검증"하는 배치다. Wallet.balance는 매 거래마다
// 갱신되는 캐시값이고, 진짜 정답은 LedgerEntry 합계여야 한다 — 둘이 어긋난다는 건 어딘가
// 버그(또는 데이터 손상)가 있다는 신호다. 이 배치는 그 신호를 "잡아내기"만 하고, 자동으로
// Wallet.balance를 고치지 않는다 — 왜 고치지 않는지는 docs/balance-reconciliation.md.
@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceReconciliationService {

	private final WalletRepository walletRepository;
	private final LedgerSumQueryRepository ledgerSumQueryRepository;
	private final BalanceDiscrepancyRepository balanceDiscrepancyRepository;

	@Transactional
	public List<BalanceDiscrepancy> reconcile() {
		Map<Long, Long> ledgerSumsByWalletId = ledgerSumQueryRepository.sumSignedAmountByWalletId();
		List<Wallet> wallets = walletRepository.findAll();

		List<BalanceDiscrepancy> discrepancies = new ArrayList<>();
		for (Wallet wallet : wallets) {
			long cachedBalance = wallet.getBalance();
			long ledgerSum = ledgerSumsByWalletId.getOrDefault(wallet.getId(), 0L);
			if (cachedBalance != ledgerSum) {
				log.error("잔액 불일치 감지: walletId={}, cachedBalance={}, ledgerSum={}, difference={}",
						wallet.getId(), cachedBalance, ledgerSum, cachedBalance - ledgerSum);
				discrepancies.add(balanceDiscrepancyRepository
						.save(BalanceDiscrepancy.of(wallet.getId(), cachedBalance, ledgerSum)));
			}
		}
		return discrepancies;
	}
}
