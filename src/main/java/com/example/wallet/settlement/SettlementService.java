package com.example.wallet.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet.common.exception.DuplicateSettlementException;
import com.example.wallet.common.exception.MerchantNotFoundException;
import com.example.wallet.merchant.Merchant;
import com.example.wallet.merchant.MerchantRepository;

import lombok.RequiredArgsConstructor;

// 한 가맹점·한 날짜 정산을 트랜잭션 하나로 처리한다. SettlementBatchRunner가 가맹점별로
// 이 메서드를 (다른 빈을 통해) 호출하므로, 한 가맹점에서 충돌이 나도 그 가맹점만 영향을
// 받고 같은 배치에서 이미 끝난 다른 가맹점의 정산은 롤백되지 않는다.
//
// 재실행 멱등성(LedgerEntry.idempotencyKey와 같은 패턴): 먼저 (merchantId, settlementDate)
// 조합으로 이미 정산이 있는지 보고, 있으면 그대로 반환한다. 없으면 계산해서 INSERT하는데,
// 동시에 같은 조합으로 다른 트랜잭션이 먼저 들어왔다면 UNIQUE 제약 위반이 나고, 그 경우
// DuplicateSettlementException을 던져서 호출자가 다시 조회하게 한다.
@Service
@RequiredArgsConstructor
public class SettlementService {

	private final SettlementRepository settlementRepository;
	private final MerchantRepository merchantRepository;

	@Transactional
	public Settlement settleOne(LocalDate date, MerchantDailyAggregate aggregate) {
		Optional<Settlement> existing = settlementRepository.findByMerchantIdAndSettlementDate(
				aggregate.merchantId(), date);
		if (existing.isPresent()) {
			return existing.get();
		}

		Merchant merchant = merchantRepository.findById(aggregate.merchantId())
				.orElseThrow(() -> new MerchantNotFoundException(aggregate.merchantId()));

		long feeAmount = calculateFee(aggregate.totalPaymentAmount(), merchant.getFeeRate());

		try {
			return settlementRepository.saveAndFlush(Settlement.of(aggregate.merchantId(), date,
					aggregate.totalPaymentAmount(), aggregate.totalRefundAmount(), feeAmount));
		} catch (DataIntegrityViolationException e) {
			throw new DuplicateSettlementException(aggregate.merchantId(), date, e);
		}
	}

	// DuplicateSettlementException을 잡은 호출자(SettlementBatchRunner)가 호출한다.
	public Settlement findSettlement(Long merchantId, LocalDate date) {
		return settlementRepository.findByMerchantIdAndSettlementDate(merchantId, date)
				.orElseThrow(() -> new IllegalStateException(
						"Settlement not found after conflict: merchantId=" + merchantId + ", date=" + date));
	}

	// ADR-004: 수수료는 결제 총액(gross) 기준으로 계산한다.
	private long calculateFee(long totalPaymentAmount, BigDecimal feeRate) {
		return BigDecimal.valueOf(totalPaymentAmount)
				.multiply(feeRate)
				.setScale(0, RoundingMode.HALF_UP)
				.longValueExact();
	}
}
