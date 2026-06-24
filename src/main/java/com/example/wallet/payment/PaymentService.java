package com.example.wallet.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.InsufficientBalanceException;
import com.example.wallet.common.exception.WalletNotFoundException;
import com.example.wallet.ledger.LedgerEntry;
import com.example.wallet.ledger.LedgerEntryRepository;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;

import lombok.RequiredArgsConstructor;

// 의도적으로 락이 없다 (S2 1~2단계). 잔액을 한 번 읽어서 확인(check)한 뒤, 그 확인 결과를 믿고
// DB에 상대 차감(-amount)을 날린다(act). 이 check와 act 사이에 다른 트랜잭션이 끼어들 수 있어서,
// 동시에 결제 2건이 들어오면 둘 다 "잔액 충분"으로 판단하고 둘 다 차감해버려 잔액이 음수가 될 수 있다.
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final WalletRepository walletRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	@Transactional
	public PaymentResponse pay(Long walletId, Long merchantId, long amount) {
		Wallet wallet = walletRepository.findById(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));

		if (wallet.getBalance() < amount) {
			throw new InsufficientBalanceException(walletId);
		}

		// 동시성 버그를 안정적으로 재현하기 위한 의도적 지연. check와 act 사이의 race window를
		// 넓혀서, 운 나쁘게 한 번씩만 일어나는 게 아니라 테스트에서 매번 재현되게 한다.
		// 실제 운영 코드라면 이런 sleep은 없고, 그래도 race는 여전히 일어날 수 있다.
		sleepToWidenRaceWindow();

		long believedBalanceAfter = wallet.getBalance() - amount;
		walletRepository.deductBalanceWithoutLock(walletId, amount);
		ledgerEntryRepository.save(LedgerEntry.payment(walletId, merchantId, amount, believedBalanceAfter));

		return new PaymentResponse(walletId, merchantId, believedBalanceAfter);
	}

	private void sleepToWidenRaceWindow() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
