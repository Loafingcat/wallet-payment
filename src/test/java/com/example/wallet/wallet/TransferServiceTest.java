package com.example.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.wallet.common.exception.InsufficientBalanceException;
import com.example.wallet.common.exception.InvalidTransferException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.ledger.LedgerType;
import com.example.wallet.support.IntegrationTestSupport;

class TransferServiceTest extends IntegrationTestSupport {

	@Autowired
	private TransferService transferService;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	private Long walletAId;
	private Long walletBId;

	@BeforeEach
	void setUp() {
		Wallet walletA = new Wallet(1L);
		walletA.charge(10_000L);
		walletAId = walletRepository.save(walletA).getId();

		Wallet walletB = new Wallet(2L);
		walletBId = walletRepository.save(walletB).getId();
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}

	@Test
	void 정상_송금하면_보내는_지갑은_줄고_받는_지갑은_늘며_원장_두_건이_남는다() {
		TransferResponse response = transferService.transfer(walletAId, walletBId, 3_000L, newKey());

		assertThat(response.fromWalletBalance()).isEqualTo(7_000L);
		assertThat(response.toWalletBalance()).isEqualTo(3_000L);

		Wallet walletA = walletRepository.findById(walletAId).orElseThrow();
		Wallet walletB = walletRepository.findById(walletBId).orElseThrow();
		assertThat(walletA.getBalance()).isEqualTo(7_000L);
		assertThat(walletB.getBalance()).isEqualTo(3_000L);

		List<LedgerEntry> aEntries = ledgerEntryRepository.findByWalletId(walletAId);
		List<LedgerEntry> bEntries = ledgerEntryRepository.findByWalletId(walletBId);
		assertThat(aEntries).hasSize(1);
		assertThat(aEntries.get(0).getType()).isEqualTo(LedgerType.TRANSFER_OUT);
		assertThat(bEntries).hasSize(1);
		assertThat(bEntries.get(0).getType()).isEqualTo(LedgerType.TRANSFER_IN);
	}

	@Test
	void 같은_키로_같은_송금을_다시_요청해도_중복_처리되지_않는다() {
		String key = newKey();

		TransferResponse first = transferService.transfer(walletAId, walletBId, 3_000L, key);
		TransferResponse second = transferService.transfer(walletAId, walletBId, 3_000L, key);

		assertThat(second).isEqualTo(first);
		Wallet walletA = walletRepository.findById(walletAId).orElseThrow();
		assertThat(walletA.getBalance()).isEqualTo(7_000L);
		assertThat(ledgerEntryRepository.findByWalletId(walletAId)).hasSize(1);
	}

	@Test
	void 같은_지갑으로_송금하면_예외가_나고_아무것도_바뀌지_않는다() {
		assertThatThrownBy(() -> transferService.transfer(walletAId, walletAId, 1_000L, newKey()))
				.isInstanceOf(InvalidTransferException.class);

		Wallet walletA = walletRepository.findById(walletAId).orElseThrow();
		assertThat(walletA.getBalance()).isEqualTo(10_000L);
		assertThat(ledgerEntryRepository.findByWalletId(walletAId)).isEmpty();
	}

	@Test
	void 잔액이_부족하면_예외가_나고_양쪽_지갑_모두_바뀌지_않는다() {
		assertThatThrownBy(() -> transferService.transfer(walletAId, walletBId, 100_000L, newKey()))
				.isInstanceOf(InsufficientBalanceException.class);

		Wallet walletA = walletRepository.findById(walletAId).orElseThrow();
		Wallet walletB = walletRepository.findById(walletBId).orElseThrow();
		assertThat(walletA.getBalance()).isEqualTo(10_000L);
		assertThat(walletB.getBalance()).isZero();
		assertThat(ledgerEntryRepository.findByWalletId(walletAId)).isEmpty();
		assertThat(ledgerEntryRepository.findByWalletId(walletBId)).isEmpty();
	}
}
