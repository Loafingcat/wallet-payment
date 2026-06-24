package com.example.wallet.wallet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WalletService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	// 이 메서드 전체가 하나의 트랜잭션이다: wallet.balance 갱신과 LedgerEntry insert가
	// 같이 commit되거나 같이 rollback된다. 둘 중 하나만 반영되면 절대 규칙 2번(원장 == 잔액)이 깨지므로
	// 트랜잭션 경계를 메서드 전체로 명시적으로 잡는다.
	@Transactional
	public ChargeResponse charge(Long walletId, long amount) {
		Wallet wallet = walletRepository.findById(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));

		wallet.charge(amount);
		ledgerEntryRepository.save(LedgerEntry.charge(walletId, amount, wallet.getBalance()));

		return new ChargeResponse(wallet.getId(), wallet.getBalance());
	}
}
