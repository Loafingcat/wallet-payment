package com.example.wallet.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;

import lombok.RequiredArgsConstructor;

// 절대 규칙 3번: 동시 결제는 같은 지갑에 대해 직렬화된다. findByIdForUpdate가 SELECT ... FOR
// UPDATE로 행을 잠그기 때문에, 두 번째 트랜잭션은 첫 번째가 commit(or rollback)할 때까지
// 이 메서드의 첫 줄에서 그냥 멈춰 있다가, 깨어난 뒤에는 갱신된 balance를 보고 다시 확인한다.
// 그래서 "확인했던 값이 쓰는 시점엔 이미 낡은 값"이 되는 일이 없다.
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	@Transactional
	public PaymentResponse pay(Long walletId, Long merchantId, long amount) {
		Wallet wallet = walletRepository.findByIdForUpdate(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));

		wallet.pay(amount);
		ledgerEntryRepository.save(LedgerEntry.payment(walletId, merchantId, amount, wallet.getBalance()));

		return new PaymentResponse(walletId, merchantId, wallet.getBalance());
	}
}
