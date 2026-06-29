package com.example.wallet.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceDiscrepancyRepository extends JpaRepository<BalanceDiscrepancy, Long> {
}
