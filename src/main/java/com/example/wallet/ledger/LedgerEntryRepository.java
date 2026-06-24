package com.example.wallet.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	List<LedgerEntry> findByWalletId(Long walletId);

	Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);
}
