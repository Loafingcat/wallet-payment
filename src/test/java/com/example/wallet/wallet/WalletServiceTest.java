package com.example.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.support.IntegrationTestSupport;

class WalletServiceTest extends IntegrationTestSupport {

	@Autowired
	private WalletService walletService;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	private Long walletId;

	@BeforeEach
	void setUp() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletId = wallet.getId();
	}

	@Test
	void 충전하면_잔액이_충전금액만큼_증가한다() {
		walletService.charge(walletId, 10_000L, newKey());

		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		assertThat(wallet.getBalance()).isEqualTo(10_000L);
	}

	@Test
	void 여러_번_충전해도_잔액은_원장_합계와_항상_일치한다() {
		walletService.charge(walletId, 10_000L, newKey());
		walletService.charge(walletId, 5_000L, newKey());
		walletService.charge(walletId, 3_000L, newKey());

		Wallet wallet = walletRepository.findById(walletId).orElseThrow();
		List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);
		long ledgerSum = entries.stream().mapToLong(LedgerEntry::getAmount).sum();

		assertThat(wallet.getBalance()).isEqualTo(18_000L);
		assertThat(wallet.getBalance()).isEqualTo(ledgerSum);
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}
}
