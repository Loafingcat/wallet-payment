package com.example.wallet.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.wallet.merchant.Merchant;
import com.example.wallet.merchant.MerchantRepository;
import com.example.wallet.payment.PaymentResponse;
import com.example.wallet.payment.PaymentService;
import com.example.wallet.payment.RefundService;
import com.example.wallet.support.IntegrationTestSupport;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;

class SettlementBatchRunnerTest extends IntegrationTestSupport {

	@Autowired
	private SettlementBatchRunner settlementBatchRunner;

	@Autowired
	private SettlementRepository settlementRepository;

	@Autowired
	private MerchantRepository merchantRepository;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private WalletService walletService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private RefundService refundService;

	private Long walletId;
	private final LocalDate today = LocalDate.now();

	@BeforeEach
	void setUp() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletId = wallet.getId();
		walletService.charge(walletId, 1_000_000L, newKey());
	}

	@Test
	void 결제와_환불이_섞인_데이터로_가맹점별_정산액을_정확히_계산한다() {
		Merchant merchantA = merchantRepository.save(new Merchant("A상점", new BigDecimal("0.0500")));
		Merchant merchantB = merchantRepository.save(new Merchant("B상점")); // 기본 수수료율 2.5%

		PaymentResponse paymentA1 = paymentService.pay(walletId, merchantA.getId(), 10_000L, newKey());
		paymentService.pay(walletId, merchantA.getId(), 20_000L, newKey());
		refundService.refund(paymentA1.paymentId(), 4_000L, newKey());

		paymentService.pay(walletId, merchantB.getId(), 5_000L, newKey());

		List<Settlement> settlements = settlementBatchRunner.run(today, null);

		Settlement settlementA = findByMerchant(settlements, merchantA.getId());
		Settlement settlementB = findByMerchant(settlements, merchantB.getId());

		// A상점: 결제 30000, 환불 4000, 수수료 = 30000 * 5% = 1500
		// 정산액 = 30000 - 4000 - 1500 = 24500
		assertThat(settlementA.getTotalPaymentAmount()).isEqualTo(30_000L);
		assertThat(settlementA.getTotalRefundAmount()).isEqualTo(4_000L);
		assertThat(settlementA.getFeeAmount()).isEqualTo(1_500L);
		assertThat(settlementA.getSettlementAmount()).isEqualTo(24_500L);

		// B상점: 결제 5000, 환불 0, 수수료 = 5000 * 2.5% = 125
		// 정산액 = 5000 - 0 - 125 = 4875
		assertThat(settlementB.getTotalPaymentAmount()).isEqualTo(5_000L);
		assertThat(settlementB.getTotalRefundAmount()).isZero();
		assertThat(settlementB.getFeeAmount()).isEqualTo(125L);
		assertThat(settlementB.getSettlementAmount()).isEqualTo(4_875L);
	}

	@Test
	void 같은_날짜를_두_번_정산해도_중복_Settlement가_생기지_않고_스냅샷이_고정된다() {
		Merchant merchant = merchantRepository.save(new Merchant("A상점", new BigDecimal("0.0500")));
		paymentService.pay(walletId, merchant.getId(), 10_000L, newKey());

		List<Settlement> firstRun = settlementBatchRunner.run(today, merchant.getId());
		assertThat(firstRun).hasSize(1);
		assertThat(firstRun.get(0).getTotalPaymentAmount()).isEqualTo(10_000L);

		// 정산 이후에 같은 날 결제가 하나 더 들어와도, 이미 끝난 정산은 다시 계산하지 않는다
		// (Settlement는 한 번 만들어지면 고정되는 스냅샷이다).
		paymentService.pay(walletId, merchant.getId(), 99_999L, newKey());

		List<Settlement> secondRun = settlementBatchRunner.run(today, merchant.getId());

		assertThat(secondRun).hasSize(1);
		assertThat(secondRun.get(0).getId()).isEqualTo(firstRun.get(0).getId());
		assertThat(secondRun.get(0).getTotalPaymentAmount()).isEqualTo(10_000L); // 99999는 반영 안 됨

		List<Settlement> stored = settlementRepository.findByMerchantIdAndSettlementDate(merchant.getId(), today)
				.map(List::of)
				.orElseGet(List::of);
		assertThat(stored).hasSize(1);
	}

	private Settlement findByMerchant(List<Settlement> settlements, Long merchantId) {
		return settlements.stream()
				.filter(s -> s.getMerchantId().equals(merchantId))
				.findFirst()
				.orElseThrow();
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}
}
