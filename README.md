# Wallet Payment — 선불 지갑 결제·정산 시스템

**한 줄 소개**: 돈이 오가는 로직을 정합성 있게, 책임감 있게 다루는 법을 보여주는 백엔드 프로젝트입니다.

## 왜 이 프로젝트인가

핀테크 채용 포트폴리오용으로 만들었습니다. 기능 개수를 늘리는 대신, **정합성·동시성·멱등성·정산 정확성**을 깊이 다루는 데 집중했습니다. 모든 설계 결정은 "이게 정합성 서사를 강화하는가?"라는 기준으로 걸러졌습니다 — 이 README의 각 섹션이 그 결정과 이유를 보여줍니다.

이 프로젝트의 하이라이트는 [동시성 문제를 어떻게 재현하고 풀었는가](#동시성-문제--이-프로젝트의-하이라이트) 섹션입니다.

## 기술 스택

Java 17, Spring Boot 3.5, Spring Data JPA + Hibernate, QueryDSL, MySQL 8, RabbitMQ, Gradle(Groovy DSL), JUnit5 + AssertJ + Testcontainers, GitHub Actions.

## 도메인 모델

```
Wallet (지갑)                LedgerEntry (거래 원장 — 불변)
─────────────────            ───────────────────────────────────
id                            id
userId (unique)               walletId          ──┐
balance  ◄── 캐시(파생값)      type (CHARGE/PAYMENT/REFUND)
version (@Version)            amount (양수)
createdAt/updatedAt           balanceAfter
                               merchantId (PAYMENT/REFUND만)
                               refundOfEntryId (REFUND만, 원결제 참조)
                               idempotencyKey (UNIQUE)
                               createdAt

Merchant (가맹점)              Settlement (정산 — 한 번 만들어지면 불변)
─────────────────            ───────────────────────────────────
id                            id
name                          merchantId + settlementDate (UNIQUE)
feeRate (BigDecimal)          totalPaymentAmount / totalRefundAmount
                               feeAmount / settlementAmount
                               createdAt
```

**관계**: `LedgerEntry.walletId`가 `Wallet`을 가리키고, `LedgerEntry.refundOfEntryId`가 환불 대상 PAYMENT `LedgerEntry`를 가리킵니다. `Settlement`는 `LedgerEntry`를 직접 집계한 결과이지 별도로 유지되는 상태가 아닙니다 — `LedgerEntry`가 모든 것의 원천입니다.

## 원장 기반 잔액 설계의 이유

`Wallet.balance`는 컬럼으로 존재하지만 **캐시일 뿐**입니다. 진실의 원천은 `LedgerEntry`의 누적 합계입니다. 그래서:

- `LedgerEntry`는 **setter가 없고, 생성 후 상태를 바꾸는 메서드가 없습니다.** UPDATE/DELETE를 할 코드 경로 자체가 존재하지 않습니다(정적 팩토리로 생성 → 1회 INSERT).
- 취소·환불은 기존 행을 고치는 게 아니라 **반대 방향의 새 행**(`REFUND`)으로 표현합니다. `refundOfEntryId`로 원결제를 참조할 뿐, 원결제 행은 절대 건드리지 않습니다.
- `Wallet.balance` 갱신과 그 변화를 일으킨 `LedgerEntry` INSERT는 **항상 같은 트랜잭션**(`@Transactional` 메서드 하나) 안에서 일어납니다. 둘 중 하나만 반영되는 일은 구조적으로 불가능합니다.

이렇게 하면 "잔액이 왜 이 값인가?"라는 질문에 항상 "원장을 더해보면 된다"로 답할 수 있고, 그 답이 캐시 컬럼과 항상 일치한다는 걸 트랜잭션 경계로 보장합니다.

## 동시성 문제 — 이 프로젝트의 하이라이트

같은 지갑에 결제 2건이 동시에 들어오면 무슨 일이 생길까요? 이걸 **버그를 먼저 만들어서 증명하고, 그다음 고치는** 과정으로 직접 보였습니다(커밋 `b0acfa9` → `a9a79a8`).

### 1단계 — 락 없이 구현, 버그 재현

`PaymentService.pay()`를 "잔액 확인 → 차감" 두 단계로 짜되, 차감을 JPQL 벌크 UPDATE(`balance = balance - :amount`, 버전 체크 없음)로 처리했습니다. 잔액 10,000원인 지갑에 6,000원 결제 2건을 `CountDownLatch`로 동시에 쏘면:

```
둘 다 성공, 잔액 -2,000원
```

각 트랜잭션이 "확인" 시점에는 서로의 존재를 모른 채 둘 다 "잔액 충분"이라고 판단하고, "차감"은 상대적 연산(`balance - amount`)이라 둘 다 그대로 적용됩니다. 이 상태를 `PaymentConcurrencyTest`로 테스트 코드에 남기고(당시는 "버그를 증명하는" 테스트), 커밋 메시지에 "동시성 버그 재현"을 명시했습니다.

### 2단계 — PESSIMISTIC_WRITE 락으로 수정

지갑 조회를 `SELECT ... FOR UPDATE`(`WalletRepository.findByIdForUpdate`)로 바꿨습니다. 이 row를 읽는 즉시 DB가 행 잠금을 걸어서, 같은 지갑을 노리는 다른 트랜잭션은 그 SELECT 문 자체에서 멈춰 기다립니다. 깨어난 뒤에는 **갱신된 최신 잔액**으로 다시 판단하므로, "확인했던 값이 쓰는 시점엔 이미 낡았다"는 일이 일어날 수 없습니다.

같은 테스트, 같은 시나리오, 결과만 바뀝니다:

```
하나만 성공(잔액 4,000원), 하나는 InsufficientBalanceException, 잔액은 절대 음수 아님
```

### 부가 효과 — 같은 락이 다른 문제도 막아줌

S4(환불)에서 "이 결제, 지금까지 얼마나 환불됐나"를 확인하는 시점과 새 환불을 기록하는 시점 사이에도 같은 종류의 race가 있을 수 있습니다. 그런데 환불도 같은 지갑의 `findByIdForUpdate`를 쓰기 때문에, 결제 이중차감을 막으려고 추가한 락이 환불 누적액 초과도 그냥 같이 막아줬습니다 — 별도 장치를 더 만들 필요가 없었습니다.

### 비관적 락 vs 낙관적 락

CLAUDE.md 규칙대로 비관적 락을 기본으로 쓰고, 낙관적 락(`@Version`) 대안 구현을 `payment/optimistic/`에 따로 만들어 비교했습니다. 트레이드오프 전체는 **[docs/optimistic-vs-pessimistic-lock.md](docs/optimistic-vs-pessimistic-lock.md)** 참고. 요약:

| | 비관적 락 (기본) | 낙관적 락 (비교용) |
|---|---|---|
| 방식 | 충돌이 안 나게 미리 막음(행 잠금) | 충돌이 난 뒤에 감지(버전 체크) + 재시도 |
| 같은 시나리오 결과 | 하나만 성공, 잔액부족으로 즉시 실패 | 하나만 성공, 버전 충돌→재시도→잔액부족으로 실패 |
| 적합한 상황 | 충돌이 잦거나 결과가 중요한 곳(결제) | 충돌이 드문 곳, 락 비용을 아끼고 싶을 때 |

### 실측: 부하 테스트로 증명 (S8)

분석만 하고 끝내지 않고, k6로 같은 지갑에 VU(가상 사용자) 10→50→100→200을 퍼부어 두 방식을 실제로 측정했습니다. 전체 방법론·환경·그래프는 **[docs/benchmark.md](docs/benchmark.md)** 참고.

| 락 방식 | VU=10 에러율 | VU=200 에러율 | VU=200 평균 시도횟수 |
|---|---|---|---|
| 비관적 | 0.0% | 0.0% | – |
| 낙관적 | 31.7% | 88.5% | 4.75 / 5 |

**결론**: 비관적 락은 경합이 심해져도(VU 200) 에러율 0%를 유지합니다(대기만 늘어날 뿐 헛수고가 없음). 낙관적 락은 경합이 심해질수록 "재시도가 재시도를 부르는" 구조라 에러율이 기하급수적으로 치솟고, 재시도 횟수도 상한(5회)에 거의 다 닿습니다. 8번의 실행 모두 끝나고 `잔액 == LedgerEntry 합계`를 직접 검증했고, 에러율 88%인 극단적 상황에서도 한 번도 어긋나지 않았습니다 — 실패한 시도는 트랜잭션이 통째로 롤백되어 흔적을 안 남기기 때문입니다.

## 멱등성 설계

충전·결제·환불 API는 모두 `Idempotency-Key` 헤더가 **필수**입니다. 별도 테이블을 만들지 않고 `LedgerEntry.idempotencyKey`에 **UNIQUE 제약**을 걸어서 풀었습니다(ADR-003).

- **같은 키로 재요청**: 처리 전에 그 키로 이미 기록된 `LedgerEntry`가 있는지 먼저 확인하고, 있으면 그 결과를 그대로 반환합니다(재계산 없음).
- **동시에 같은 키로 요청 두 개**: 둘 다 "키 없음"을 보고 처리를 시도할 수 있습니다. 늦게 INSERT하는 쪽은 UNIQUE 제약 위반으로 실패 → 그 트랜잭션 전체(잔액 변경 포함)가 롤백 → 컨트롤러가 먼저 커밋된 거래의 결과를 다시 조회해 반환합니다.
- **왜 별도 테이블이 아닌가**: 충전/결제/환불 응답이 전부 `LedgerEntry` 한 행에서 그대로 재구성되는 단순한 구조라서, 응답을 따로 저장할 필요가 없었습니다(ADR-003에 트레이드오프 전체 기록).
- 같은 (DB 제약 → 충돌 감지 → 재조회) 패턴이 정산(`Settlement`의 `(merchantId, settlementDate)` UNIQUE)과 결제완료 이벤트 컨슈머(`ProcessedPaymentEvent`)에도 반복해서 쓰였습니다 — 이 프로젝트에서 가장 많이 재사용된 아이디어입니다.

## 정산 로직과 멱등 재실행

`POST /settlements/run?date=2026-06-01&merchantId=(선택)`로 일 단위 정산을 수동 트리거할 수 있고, `@Scheduled`로 매일 새벽 1시에 전날 전체 가맹점을 자동 정산합니다.

- **집계**: `QueryDSL`(`BooleanBuilder` + `CaseBuilder`)로 기간·가맹점 동적 필터를 조립해 `LedgerEntry`에서 직접 `SUM(CASE WHEN type='PAYMENT' ...)` / `SUM(CASE WHEN type='REFUND' ...)`을 구합니다. Spring Data 메서드 이름 규칙으로는 "필터가 있을 수도 없을 수도 있는" 쿼리를 표현할 수 없어서 QueryDSL을 선택했습니다.
- **수수료**: `Merchant.feeRate`(기본 2.5%, ADR-004) × 결제 총액(gross, 환불과 무관하게 계산).
- **재실행 멱등성**: `Settlement`는 `(merchantId, settlementDate)`에 UNIQUE 제약이 걸려 있고, 한 번 만들어지면 다시 계산하지 않는 **스냅샷**입니다. 같은 날짜를 두 번 정산해도 중복 행이 생기지 않고, 정산 이후에 같은 날 결제가 더 들어와도 이미 끝난 정산 결과는 바뀌지 않습니다(`SettlementBatchRunnerTest`로 검증).
- **가맹점별·기간 매출 조회**: `GET /merchants/{id}/stats?from=&to=`, `GET /merchants/stats?from=&to=`가 같은 QueryDSL 집계 메서드를 재사용해 임의 기간 통계를 보여줍니다.

## API 목록

| Method | Path | 설명 | 헤더 |
|---|---|---|---|
| GET | `/health` | 헬스체크 | - |
| POST | `/wallets/{id}/charge` | 충전 | `Idempotency-Key` |
| POST | `/payments` | 결제 | `Idempotency-Key` |
| POST | `/payments/{id}/refund` | 환불(전액/부분) | `Idempotency-Key` |
| POST | `/settlements/run?date=&merchantId=` | 정산 수동 트리거 | - |
| GET | `/merchants/{id}/stats?from=&to=` | 가맹점별 기간 매출 | - |
| GET | `/merchants/stats?from=&to=` | 전체 가맹점 기간 매출 | - |

## 실행 방법

```bash
# 1. MySQL + RabbitMQ 띄우기
docker compose up -d

# 2. 테스트 (Testcontainers가 MySQL/RabbitMQ를 자동으로 띄워서 검증)
./gradlew test

# 3. 앱 실행 (local 프로파일)
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. 확인
curl http://localhost:8080/health
```

RabbitMQ 관리 UI: http://localhost:15672 (guest/guest)

CI: `main`/`master`로 push하거나 PR을 열면 GitHub Actions가 `./gradlew test`를 자동 실행합니다(`.github/workflows/ci.yml`) — Testcontainers는 러너에 이미 있는 Docker를 그대로 씁니다.

## 더 읽을거리

- [docs/decisions.md](docs/decisions.md) — ADR 전체 (금액 타입, 멱등키 저장 방식, 수수료율 등)
- [docs/optimistic-vs-pessimistic-lock.md](docs/optimistic-vs-pessimistic-lock.md) — 락 전략 비교
- `git log --oneline` — 단계별(S0~S7) 커밋 히스토리. 특히 `b0acfa9`(버그 재현) → `a9a79a8`(수정) 두 커밋의 diff가 이 프로젝트의 핵심입니다.
