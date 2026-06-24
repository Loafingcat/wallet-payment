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

## ADR-003: 멱등성 키는 별도 테이블이 아니라 LedgerEntry.idempotencyKey + UNIQUE 제약으로 관리

- **날짜**: 2026-06-24
- **후보**:
  1. 별도 `idempotency_key` 테이블에 요청/응답을 통째로 저장. 실패 응답까지 재생 가능하지만 테이블과 트랜잭션 관리가 하나 더 늘어난다.
  2. S1에서 만든 `LedgerEntry.idempotencyKey` 컬럼에 UNIQUE 제약을 걸어서 그대로 쓴다.
- **결정**: 2번. `LedgerEntry.idempotencyKey`를 `nullable = false, unique = true`로 바꾸고, 같은 키로 다시 INSERT가 들어오면 DB가 막아준다.
- **이유**: 충전/결제 응답은 `{walletId, balance}`뿐이라 LedgerEntry 한 행에서 그대로 재구성된다 — 응답을 별도로 저장할 이유가 없다. 새 테이블 없이 기존 컬럼(S1에서 "nullable 지금은"으로 남겨뒀던 것)을 완성하는 것만으로 충분하다.
- **트레이드오프**: 멱등성이 "성공한 거래"에만 적용된다. 첫 시도가 잔액부족 등으로 실패하면 LedgerEntry가 안 남으므로 같은 키로 재시도해도 실패 응답이 그대로 재생되지 않고 처음부터 다시 시도된다. 실패한 시도는 부작용이 없으므로 다시 시도해도 안전하다고 보고 이 정도 단순화는 받아들인다.
- **동시에 같은 키로 요청이 들어올 때**: 멱등키 존재 여부를 먼저 확인하고, 없으면 정상 처리 후 `idempotencyKey`를 채워 INSERT한다. 두 요청이 동시에 "존재 안 함"을 보고 둘 다 처리를 시도하면, 둘 중 하나의 INSERT는 UNIQUE 제약을 위반해 실패한다 — 그 트랜잭션 전체(잔액 변경 포함)를 롤백하고, 먼저 커밋된 거래의 결과를 다시 조회해서 반환한다.
