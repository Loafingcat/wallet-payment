package com.example.wallet.settlement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

	Optional<Settlement> findByMerchantIdAndSettlementDate(Long merchantId, LocalDate settlementDate);

	List<Settlement> findBySettlementDate(LocalDate settlementDate);
}
