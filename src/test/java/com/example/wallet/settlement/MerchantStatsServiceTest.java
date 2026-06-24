package com.example.wallet.settlement;

import static org.assertj.core.api.Assertions.assertThat;

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

class MerchantStatsServiceTest extends IntegrationTestSupport {

	@Autowired
	private MerchantStatsService merchantStatsService;

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
	private final LocalDate yesterday = LocalDate.now().minusDays(1);
	private final LocalDate tomorrow = LocalDate.now().plusDays(1);

	@BeforeEach
	void setUp() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		walletId = wallet.getId();
		walletService.charge(walletId, 1_000_000L, newKey());
	}

	@Test
	void 특정_가맹점의_기간_매출을_정확히_집계한다() {
		Merchant merchant = merchantRepository.save(new Merchant("A상점"));

		PaymentResponse payment = paymentService.pay(walletId, merchant.getId(), 30_000L, newKey());
		refundService.refund(payment.paymentId(), 5_000L, newKey());

		MerchantStatsResponse stats = merchantStatsService.statsFor(merchant.getId(), yesterday, tomorrow);

		assertThat(stats.totalPaymentAmount()).isEqualTo(30_000L);
		assertThat(stats.totalRefundAmount()).isEqualTo(5_000L);
		assertThat(stats.netAmount()).isEqualTo(25_000L);
	}

	@Test
	void 거래가_없는_가맹점은_전부_0으로_나온다() {
		Merchant merchant = merchantRepository.save(new Merchant("거래없음상점"));

		MerchantStatsResponse stats = merchantStatsService.statsFor(merchant.getId(), yesterday, tomorrow);

		assertThat(stats.totalPaymentAmount()).isZero();
		assertThat(stats.totalRefundAmount()).isZero();
		assertThat(stats.netAmount()).isZero();
	}

	@Test
	void 가맹점_필터_없이_조회하면_거래가_있었던_모든_가맹점이_나온다() {
		Merchant merchantA = merchantRepository.save(new Merchant("A상점"));
		Merchant merchantB = merchantRepository.save(new Merchant("B상점"));

		paymentService.pay(walletId, merchantA.getId(), 10_000L, newKey());
		paymentService.pay(walletId, merchantB.getId(), 20_000L, newKey());

		List<MerchantStatsResponse> stats = merchantStatsService.statsForAllMerchants(yesterday, tomorrow);

		assertThat(stats).extracting(MerchantStatsResponse::merchantId)
				.contains(merchantA.getId(), merchantB.getId());
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}
}
