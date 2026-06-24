# 비관적 락 vs 낙관적 락 (결제 동시성)

CLAUDE.md 절대 규칙 3번: 기본은 비관적 락(`PESSIMISTIC_WRITE`)이고, 낙관적 락(`@Version`) 방식도 별도로 비교 분석한다. 이 문서가 그 비교다.

## 코드 위치

| | 비관적 락 (기본, 실제 API가 쓰는 경로) | 낙관적 락 (비교용 대안) |
|---|---|---|
| 지갑 조회 | `WalletRepository.findByIdForUpdate` — `SELECT ... FOR UPDATE` | `WalletRepository.findById` — 락 없음 |
| 충돌 처리 | 없음(애초에 충돌이 안 일어남, 두 번째 트랜잭션이 줄을 서서 기다림) | `OptimisticPaymentService`가 `ObjectOptimisticLockingFailureException`을 잡아 최대 5번 재시도 |
| 위치 | `payment/PaymentService.java` | `payment/optimistic/` |

## 동작 차이

**비관적 락**: 지갑 row를 읽는 순간 DB가 행 잠금을 건다. 같은 지갑을 노리는 다른 트랜잭션은 그 SELECT 문 자체에서 멈춰서 기다린다. 충돌이 "일어나지 않게" 미리 막는 방식이다.

**낙관적 락**: 아무도 안 막는다. 모두가 동시에 읽고, 동시에 계산하고, commit 시점에 가서야 `UPDATE wallet SET ... WHERE id=? AND version=?`이 실행된다. 먼저 commit한 쪽은 성공하고 version이 올라간다. 늦게 commit하는 쪽은 자기가 읽었던 version이 이미 낡았으므로 0행이 매치되어 `ObjectOptimisticLockingFailureException`이 터진다 — "충돌이 일어난 다음에" 알아차리는 방식이다.

`OptimisticPaymentConcurrencyTest`로 직접 확인한 결과(잔액 10000, 6000원 결제 2건 동시 요청):

| | 비관적 락 | 낙관적 락 |
|---|---|---|
| 결과 | 하나만 성공(잔액 4000), 하나는 실패 | 하나만 성공(잔액 4000), 하나는 실패 |
| 실패 사유 | `InsufficientBalanceException` (대기 후 깨어나서 최신 잔액으로 재검증) | 1차: `ObjectOptimisticLockingFailureException` → 재시도 → 2차: `InsufficientBalanceException` |
| 클라이언트가 보는 예외 | `InsufficientBalanceException` | `InsufficientBalanceException` (버전 충돌은 재시도 루프 안에서 흡수돼 밖으로 안 나감) |

최종 결과(잔액, 성공/실패 개수)는 동일하다. 차이는 **그 결과에 도달하는 경로**다.

## 트레이드오프

| 기준 | 비관적 락 (`PESSIMISTIC_WRITE`) | 낙관적 락 (`@Version` + 재시도) |
|---|---|---|
| 충돌이 잦을 때 | 안정적 — 대기만 늘어날 뿐 헛수고가 없다 | 재시도가 쌓이면서 같은 작업을 여러 번 다시 계산함(낭비), 최악의 경우 재시도 한도 초과로 실패 |
| 충돌이 드물 때 | 락을 거는 비용이 거의 항상 "낭비"(어차피 충돌 안 났을 텐데 매번 잠금/대기 비용을 짊어짐) | 거의 항상 한 번에 성공 — 잠금 비용 자체가 없어서 더 가볍다 |
| 구현 복잡도 | 단순하다 — 조회 메서드 하나에 `@Lock`만 붙이면 끝 | 재시도 루프, self-invocation을 피하기 위한 빈 분리(`OptimisticPaymentService`/`OptimisticPaymentWriter`)가 필요해 코드가 한 단계 더 복잡 |
| 데드락 위험 | 이 프로젝트처럼 "지갑 하나"에 거는 단일 row 락이면 거의 없음. 여러 row를 순서 다르게 잠그는 경우엔 위험 | 없음(락을 안 거니까) |
| 실패 시 사용자 경험 | 결과를 한 번에 알 수 있음(대기는 하지만 재요청 불필요) | 재시도 자체는 서버 안에서 투명하게 처리되지만, 재시도 횟수가 늘수록 응답 지연이 커짐 |

## 왜 이 프로젝트는 비관적 락을 기본으로 했는가

결제는 "충돌이 드물게만 일어나는" 영역이 아니다 — 같은 사용자가 여러 기기에서 동시에 결제를 시도하거나, 클라이언트의 재시도 로직이 겹치는 일이 실제로 일어날 수 있는, **충돌의 결과가 곧 돈과 직결되는** 영역이다. 이런 영역에서는:

1. 코드가 단순할수록 검증하기 쉽다 — `@Lock` 하나로 끝나는 비관적 락은 "이게 정말 안전한가"를 추론하기 쉽다. 낙관적 락은 재시도 횟수, 재시도 사이의 간격, 재시도가 다 실패했을 때의 처리까지 같이 설계해야 한다.
2. 충돌이 잦을 가능성을 배제할 수 없는 도메인에서는, "대기"가 "낭비된 재계산"보다 다루기 쉬운 비용이다.

그래서 CLAUDE.md가 정한 대로 비관적 락을 기본으로 쓰고, 낙관적 락은 이 문서와 `payment/optimistic/` 패키지로 비교 대상으로만 남겨뒀다.
