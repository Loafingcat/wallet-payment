# Architecture Decision Records

## ADR-001: 금액 타입 — long (KRW 최소 화폐 단위)

- **날짜**: 2026-06-24
- **결정**: `Wallet.balance`, `LedgerEntry.amount`/`balanceAfter`는 `BigDecimal`이 아닌 `long`(원 단위 정수)으로 다룬다.
- **이유**: 원화는 소수점이 없는 최소 화폐 단위(1원)라 정수로 정확히 표현 가능하다. `long`은 `BigDecimal`보다 연산이 단순하고 반올림 정책을 둘 필요가 없어 정합성 버그 여지가 적다. DB 컬럼도 `BIGINT` 하나로 단순해진다.
- **트레이드오프**: 향후 해외 결제 등 소수 단위 화폐를 지원해야 하면 타입을 다시 설계해야 한다. 현재 스코프(원화 단일 통화)에서는 문제 없음.

## ADR-002: LedgerEntry.amount는 항상 양수 + type으로 방향 표현

- **날짜**: 2026-06-24
- **결정**: `amount`는 항상 양수(절대값)로 저장하고, 거래가 잔액을 늘렸는지 줄였는지는 `type`(CHARGE/PAYMENT/REFUND/SETTLEMENT_FEE)으로 표현한다.
- **이유**: 부호를 amount에 같이 넣으면 거래 방향을 알기 위해 매번 type과 amount 부호를 같이 봐야 한다. type만 보고 방향을 판단할 수 있게 하면 정산/집계 로직에서 실수가 줄어든다.
