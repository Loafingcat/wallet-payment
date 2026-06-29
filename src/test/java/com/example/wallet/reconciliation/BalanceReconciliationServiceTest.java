package com.example.wallet.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.wallet.support.IntegrationTestSupport;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

class BalanceReconciliationServiceTest extends IntegrationTestSupport {

	@Autowired
	private BalanceReconciliationService balanceReconciliationService;

	@Autowired
	private BalanceDiscrepancyRepository balanceDiscrepancyRepository;

	@Autowired
	private WalletService walletService;

	@Autowired
	private WalletRepository walletRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void 캐시_잔액과_원장_합계가_같으면_불일치가_기록되지_않는다() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletService.charge(wallet.getId(), 10_000L, UUID.randomUUID().toString());

		List<BalanceDiscrepancy> discrepancies = balanceReconciliationService.reconcile();

		assertThat(discrepancies).isEmpty();
		assertThat(balanceDiscrepancyRepository.findAll()).isEmpty();
	}

	@Test
	void 캐시_잔액과_원장_합계가_다르면_불일치를_기록한다() {
		Wallet wallet = walletRepository.save(new Wallet(2L));
		walletService.charge(wallet.getId(), 10_000L, UUID.randomUUID().toString());

		// 정상 경로(WalletService/TransferService)를 거치지 않고 캐시 잔액만 직접
		// 망가뜨린다 — 버그나 데이터 손상으로 잔액과 원장이 어긋난 상황을 흉내낸다.
		// JPQL bulk update는 @Version 체크를 거치지 않고 그대로 UPDATE를 내보낸다.
		entityManager.createQuery("UPDATE Wallet w SET w.balance = :balance WHERE w.id = :id")
				.setParameter("balance", 7_000L)
				.setParameter("id", wallet.getId())
				.executeUpdate();
		entityManager.clear();

		List<BalanceDiscrepancy> discrepancies = balanceReconciliationService.reconcile();

		assertThat(discrepancies).hasSize(1);
		BalanceDiscrepancy discrepancy = discrepancies.get(0);
		assertThat(discrepancy.getWalletId()).isEqualTo(wallet.getId());
		assertThat(discrepancy.getCachedBalance()).isEqualTo(7_000L);
		assertThat(discrepancy.getLedgerSum()).isEqualTo(10_000L);
		assertThat(discrepancy.getDifference()).isEqualTo(-3_000L);
		assertThat(balanceDiscrepancyRepository.findAll()).hasSize(1);
	}
}
