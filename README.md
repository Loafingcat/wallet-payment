# Wallet Payment — 선불 지갑 결제·정산 시스템

**한 줄 소개**: 돈이 오가는 로직을 정합성 있게, 책임감 있게 다루는 법을 보여주는 백엔드 프로젝트입니다.

## 왜 이 프로젝트인가

핀테크 채용 포트폴리오용으로 만들었습니다. 기능 개수를 늘리는 대신, **정합성·동시성·멱등성·정산 정확성**을 깊이 다루는 데 집중했습니다. 모든 설계 결정은 "이게 정합성 서사를 강화하는가?"라는 기준으로 걸러졌습니다 — 이 README의 각 섹션이 그 결정과 이유를 보여줍니다.

## 핵심 하이라이트

이 프로젝트를 한 문장으로 줄이면: **돈은 항상 셀 수 있어야 하고(원장), 동시에 들어와도 깨지지 않아야 하고(동시성), 한 다리 건너 실패해도 새거나 멈추지 않아야 한다(분산 정합성)**. 아래 네 가지가 그걸 직접 증명합니다.

1. **[동시성 버그를 먼저 만들고, 그다음 고친다](#동시성-버그-재현부터-실측까지)** — 락 없는 결제가 잔액을 음수로 만드는 것(S2), 정렬 없는 락이 송금을 데드락에 빠뜨리는 것(S11)을 각각 실패하는 테스트로 먼저 증명한 뒤, 비관적 락과 락 순서 고정으로 고쳤습니다.
2. **[락 전략을 추측이 아니라 실측으로 비교한다](#실측-부하-테스트로-증명-s8)** — 비관적 락 vs 낙관적 락을 k6로 VU 10→200까지 퍼부어, 경합이 심해질수록 에러율이 갈리는 지점을 숫자로 보여줍니다.
3. **[2PC 없이 분산 정합성을 푼다](#분산-정합성-pg-장애부터-메시지-유실-잔액-검증까지)** — 외부 PG 장애(타임아웃/5xx/응답유실, S9)는 멱등성+상태기계+보정으로, 메시지 유실(S10)은 Transactional Outbox로, 캐시 잔액과 원장의 어긋남(S11)은 탐지 전용 배치로 — 서로 다른 "확신할 수 없는 순간"을 각각 다른 도구로 다룹니다.
4. **[멱등성 패턴 하나를 끝까지 재사용한다](#멱등성-설계)** — UNIQUE 제약 → 충돌 감지 → 재조회라는 같은 패턴이 충전·결제·환불·정산·이벤트 컨슈머·PG 연동까지 반복해서 등장합니다.

## 기술 스택

Java 17, Spring Boot 3.5, Spring Data JPA + Hibernate, QueryDSL, MySQL 8, RabbitMQ, Gradle(Groovy DSL), JUnit5 + AssertJ + Testcontainers, GitHub Actions.

## Streamlit Cloud 데모

이 저장소에는 이력서 링크로 바로 열어볼 수 있는 브라우저용 데모도 포함되어 있습니다. 실제 Spring Boot/MySQL/RabbitMQ 운영 서버를 Streamlit Cloud에 그대로 올린 것이 아니라, 방문자가 **"이 프로젝트가 어떤 정합성 문제를 다루는지"** 빠르게 볼 수 있도록 핵심 흐름을 인메모리로 재현한 companion app입니다.

- 엔트리 파일: `streamlit_app.py`
- 의존성: `requirements.txt`
- 실행: `streamlit run streamlit_app.py`

### Streamlit Cloud에 배포하기

1. GitHub에 이 저장소를 push합니다.
2. Streamlit Cloud에서 **New app**을 누릅니다.
3. Repository를 이 저장소로 선택합니다.
4. Branch는 배포할 브랜치(`master` 또는 `main`)를 선택합니다.
5. Main file path에 `streamlit_app.py`를 입력합니다.
6. Deploy를 누릅니다.

Streamlit Cloud는 루트의 `requirements.txt`를 읽어서 `streamlit`을 설치합니다. 별도 DB, RabbitMQ, Docker, secrets 설정은 필요 없습니다.

### 로컬에서 실행하기

Python과 Streamlit이 설치되어 있다면:

```bash
pip install -r requirements.txt
streamlit run streamlit_app.py
```

`uv`를 쓴다면:

```bash
uv run --with streamlit==1.46.1 streamlit run streamlit_app.py
```

브라우저가 열리면 `http://localhost:8501` 또는 터미널에 표시된 Local URL로 접속합니다. 이미 8501 포트가 사용 중이면 Streamlit이 다른 포트를 안내하거나, 직접 `--server.port 8502`처럼 지정하면 됩니다.

### 화면 구성

앱은 위에서 아래로 다음 순서로 구성되어 있습니다.

| 영역 | 용도 |
|---|---|
| 지갑 | 각 사용자 지갑의 캐시 잔액과 원장 합계 차이를 보여줍니다. 정상 상태라면 차이가 0원입니다. |
| 시나리오 버튼 | 동시성 버그, 비관적 락, PG 보정, Outbox 발행, 잔액 불일치, 정산 같은 핵심 상황을 한 번에 재현합니다. |
| 거래 실행 | 충전, 결제, 환불, 송금, PG 결제를 직접 실행합니다. 각 요청에는 `Idempotency-Key`가 붙습니다. |
| 이벤트 로그 | 방금 실행한 동작이 시스템에서 어떻게 처리됐는지 짧게 보여줍니다. |
| 원장/PG/Outbox/잔액 검증/정산 탭 | 내부 상태를 테이블로 보여줍니다. "돈이 왜 이 값인지"를 확인할 때 보는 영역입니다. |

### 3분 데모 순서

처음 보는 사람에게 보여줄 때는 아래 순서가 가장 이해하기 쉽습니다.

1. **초기 상태 확인**
   - 상단의 `지갑` 영역을 봅니다.
   - `사용자 A`, `사용자 B`의 잔액과 `원장 차이 0원`을 확인합니다.
   - 아래 `원장` 탭을 열면 초기 충전 기록이 들어 있습니다.

2. **일반 결제와 원장 확인**
   - `거래 실행` → `결제` 탭에서 지갑, 가맹점, 금액을 선택하고 `결제`를 누릅니다.
   - 상단 지갑 잔액이 줄어듭니다.
   - `원장` 탭에 `결제` 행이 새로 생깁니다.
   - `Outbox` 탭에는 결제 완료 이벤트가 `PENDING` 상태로 남습니다.

3. **Transactional Outbox 확인**
   - `시나리오` 영역에서 `Outbox 발행`을 누릅니다.
   - `Outbox` 탭의 상태가 `PUBLISHED`로 바뀝니다.
   - 하단 caption의 소비자 멱등 처리 완료 이벤트 수도 증가합니다.
   - 이 흐름은 실제 백엔드의 `OutboxEvent` + `OutboxRelay` + `ProcessedPaymentEvent` 구조를 단순화한 것입니다.

4. **동시성 버그와 수정 비교**
   - `락 없는 버그`를 누릅니다.
   - 같은 지갑에서 6,000원 결제 두 건이 동시에 성공한 것처럼 처리되어 잔액이 음수가 됩니다.
   - `비관적 락`을 누릅니다.
   - 같은 조건에서 첫 결제만 성공하고 두 번째 결제는 최신 잔액 기준으로 거절됩니다.
   - 실제 백엔드에서는 `WalletRepository.findByIdForUpdate()`의 `PESSIMISTIC_WRITE` 락으로 이 문제를 막습니다.

5. **PG 장애와 보정 확인**
   - `거래 실행` → `PG 결제` 탭으로 갑니다.
   - `PG 응답`을 `timeout` 또는 `lost-response`로 선택하고 `PG 승인 요청`을 누릅니다.
   - `PG 상태` 탭에서 결제가 `PENDING_PG`로 남은 것을 확인합니다.
   - `시나리오` 영역에서 `PG 보정`을 누릅니다.
   - PG 쪽 승인 기록을 조회한 것으로 보고 `APPROVED`로 확정되며, 그때 원장에 결제가 반영됩니다.
   - 이 흐름은 실제 백엔드의 `ExternalPaymentService` + `PaymentReconciliationService` 구조를 보여줍니다.

6. **잔액 정합성 점검**
   - `잔액 깨뜨리기`를 누릅니다.
   - 상단 지갑의 `원장 차이`가 0원이 아니게 됩니다.
   - `잔액 검증` 탭을 열면 캐시 잔액(`Wallet.balance`)과 원장 합계(`LedgerEntry` 합계)의 불일치가 표시됩니다.
   - 실제 백엔드는 이런 상황을 자동 수정하지 않고 `BalanceDiscrepancy`로 기록해 사람이 조사하게 합니다.

7. **정산 스냅샷 확인**
   - 결제나 환불을 몇 건 만든 뒤 `오늘 정산`을 누릅니다.
   - `정산` 탭에 가맹점별 총결제액, 총환불액, 수수료, 정산액이 표시됩니다.
   - 같은 날짜에 다시 `오늘 정산`을 눌러도 이미 만든 스냅샷은 다시 계산하지 않습니다.

### 직접 눌러볼 기능

| 기능 | 어디서 실행 | 확인할 것 |
|---|---|---|
| 충전 | `거래 실행` → `충전` | 잔액 증가, `원장` 탭에 `충전` 행 추가 |
| 결제 | `거래 실행` → `결제` | 잔액 감소, 원장 행 추가, Outbox 이벤트 생성 |
| 환불 | `거래 실행` → `환불` | 원결제 금액을 초과하지 않는 범위에서 잔액 복구 |
| 송금 | `거래 실행` → `송금` | 출금/입금 지갑에 `TRANSFER_OUT`, `TRANSFER_IN` 쌍으로 기록 |
| PG 결제 | `거래 실행` → `PG 결제` | 정상/timeout/lost-response/5xx 케이스별 상태 변화 |
| 멱등성 | 같은 `Idempotency-Key`로 같은 요청 재실행 | 새 원장이 생기지 않고 기존 결과 반환 |

### 데모 앱과 실제 백엔드의 차이

이 데모는 설명용이라 다음을 단순화했습니다.

- 데이터는 Streamlit 세션 메모리에만 저장됩니다. 새로고침이나 재배포 후에는 초기화될 수 있습니다.
- 실제 DB 트랜잭션, MySQL row lock, RabbitMQ 발행은 사용하지 않습니다.
- 동시성은 실제 스레드 경합이 아니라 버튼으로 재현한 시나리오입니다.
- 정산 날짜는 데모 편의를 위해 오늘 날짜를 사용합니다.

정합성의 실제 구현과 검증은 Spring Boot 코드와 테스트가 담당합니다. 데모 앱은 이력서 링크에서 프로젝트의 핵심 아이디어를 빠르게 보여주기 위한 프론트 도구입니다.

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

## 동시성 버그 재현부터 실측까지

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

### 데드락: 두 번째 동시성 버그 (S11)

지갑 하나만 잠그면 끝나는 결제와 달리, 지갑 간 송금은 **출금 지갑과 입금 지갑 두 개를 한 트랜잭션에서 동시에** 잠가야 합니다. 같은 비관적 락이 여기서는 새로운 문제를 만듭니다 — 데드락입니다. 이번에도 같은 패턴(버그 재현 → 수정)을 반복했습니다(커밋 `5c4c23a` → `b56604e`).

- **재현**: 인자로 받은 순서 그대로(`fromWalletId` 먼저, `toWalletId` 다음) 지갑을 잠그면, A→B 송금과 B→A 송금이 동시에 들어왔을 때 하나는 A를 쥐고 B를 기다리고 다른 하나는 B를 쥐고 A를 기다리는 **순환 대기**가 생길 수 있습니다. `TransferDeadlockTest`가 양방향 10쌍을 동시에 쏴서 이를 신뢰성 있게 재현하고, MySQL이 둘 중 하나를 강제로 롤백시키는 것(`PessimisticLockingFailureException`)을 확인합니다.
- **수정**: **두 지갑 중 id가 더 작은 쪽을 항상 먼저 잠그도록** 전역 락 순서를 고정했습니다. 같은 지갑 쌍을 건드리는 모든 송금(양방향)이 항상 같은 순서로 락을 요청하게 되므로, 순환 대기의 전제 자체가 성립하지 않습니다 — 타이밍과 무관하게 구조적으로 데드락이 불가능해집니다. 같은 테스트가 이제는 "데드락이 발생하지 않고, 모든 송금이 성공하며, 양방향 상쇄 후 잔액이 원래대로 돌아온다"를 증명합니다.
- **남는 문제(단순 대기)**: 락 순서를 고정해도 한쪽이 다른 트랜잭션에 오래 막혀 있을 가능성은 남습니다(데드락이 아니라 단순 대기). MySQL 기본값(`innodb_lock_wait_timeout` 50초)까지 무한정 기다리지 않도록, 송금 트랜잭션의 세션에서만 이 값을 3초로 줄입니다. JPA 표준 `jakarta.persistence.lock.timeout` 힌트를 먼저 시도했으나 MySQL 다이얼렉트에서 실제로 적용되지 않는 걸 `TransferLockTimeoutTest`로 직접 확인하고, 세션 변수(`SET innodb_lock_wait_timeout`)를 직접 설정하는 방식으로 바꿨습니다 — "표준 API니까 동작할 것"이라고 가정하지 않고 테스트로 검증한 사례입니다.

전체 설명은 **[docs/deadlock-prevention.md](docs/deadlock-prevention.md)** 참고.

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

## 분산 정합성: PG 장애부터 메시지 유실, 잔액 검증까지

내 DB만 바꾸면 끝나는 결제(S2)와 달리, 한 다리 건너 외부 시스템(PG, 메시지 브로커)이 끼거나 캐시값이 진실의 원천과 슬쩍 어긋날 수 있는 지점들이 있습니다. 세 가지 서로 다른 "확신할 수 없는 순간"을 2PC 없이 각각 다른 도구로 풀었습니다.

### 외부 PG 연동과 상태기계 + 보정 (S9)

`POST /payments`(S2)는 "우리 DB만 바꾸면 끝나는" 결제입니다. `POST /payments/external`(S9)은 거기에 **외부 PG 승인**이라는, 우리가 통제할 수 없는 변수를 추가합니다. 전체 설계(왜 2PC를 안 썼는지, 상태 다이어그램, 장애 케이스별 처리)는 **[docs/distributed-consistency.md](docs/distributed-consistency.md)** 참고. 핵심만 요약하면:

- **`Payment` 엔티티는 의도적으로 가변입니다.** `LedgerEntry`(불변)와 달리 `PENDING_PG → APPROVED/FAILED`로 상태가 바뀝니다 — 이건 "확정된 사실"이 아니라 "지금까지 파악한 PG 승인 진행 상황"이기 때문입니다. 돈은 `APPROVED`(PG 승인 확인됨)가 되는 순간에만, `LedgerEntry` insert와 함께 움직입니다.
- **타임아웃과 응답유실은 클라이언트 입장에서 증상이 같지만(응답 없음), PG 쪽 진실은 다릅니다**(아직 처리 전 vs 이미 처리 완료). 그래서 둘 다 일단 `PENDING_PG`로 유보하고, 보정(`PaymentReconciliationService`)이 PG에 직접 물어봐서(`GET /pg/payments/{key}`) 사후에 진실을 확정합니다. "응답을 못 받음"을 "실패"로 단정하지 않는 게 이 설계의 핵심입니다.
- **PG 호출은 절대 `@Transactional` 메서드 안에 들어가지 않습니다**(절대 규칙 6번, S6의 RabbitMQ 발행과 같은 원칙). `ExternalPaymentWriter`의 짧은 트랜잭션 세 개 사이에, 트랜잭션 없는 `ExternalPaymentService`가 PG 호출을 끼워 넣습니다.
- 가짜 PG(`fake-pg/`, 별도 Spring Boot 모듈, 포트 9999)는 `X-Simulate-Failure` 헤더로 `timeout`/`error5xx`/`lost-response` 장애를 결정적으로 재현할 수 있습니다.

### 메시지 유실 방지: Transactional Outbox (S10)

S6에서 "DB 커밋 후 RabbitMQ 발행"(`@TransactionalEventListener(AFTER_COMMIT)`)으로 "커밋 전 발행" 문제는 막았지만, **"커밋은 됐는데 발행 직전에 프로세스가 죽으면 이벤트가 영원히 사라지는"** 문제가 남아있었습니다. 전체 비교와 테스트는 **[docs/outbox-pattern.md](docs/outbox-pattern.md)** 참고.

- **`OutboxEvent`를 비즈니스 데이터(LedgerEntry)와 같은 로컬 트랜잭션에 INSERT합니다.** 그래서 결제 트랜잭션이 커밋되는 순간 "발행해야 할 이벤트가 있다"는 사실도 항상 같이 영속화됩니다 — 더 이상 메모리에만 잠깐 존재하는 상태가 아닙니다.
- **`OutboxRelay`가 2초마다 `PENDING` 행을 읽어 RabbitMQ로 발행**하고, 성공한 것만 `PUBLISHED`로 표시합니다. 릴레이가 언제 죽든, 재시작하면 `PENDING` 행을 그대로 찾아서 다시 시도합니다 — **유실 0**(at-least-once).
- 대신 "발행 성공 후 `PUBLISHED` 표시 전에 죽으면" 같은 메시지가 중복 발행될 수 있습니다 — S6에서 이미 만든 소비자 멱등성(`ProcessedPaymentEvent`)이 그대로 막아줍니다(새 코드 추가 없이).

### 잔액 정합성 점검 배치 (S11)

"원장 기반 설계라 잔액과 원장이 항상 같다"는 보장은 코드 경로가 그렇게 짜여 있다는 뜻일 뿐, 운영 중 한 번도 안 어긋난다는 보장은 아닙니다 — 버그, 수동 운영 작업, 마이그레이션 실수가 그 보장을 깰 수 있습니다. `BalanceReconciliationService`가 모든 지갑의 캐시 잔액(`Wallet.balance`)과 원장 합계(`LedgerEntry` 누적, QueryDSL로 DB에서 직접 집계)를 비교해서 어긋난 지갑을 찾는, 2차 방어선입니다. 매일 새벽 2시 자동 실행되고, `POST /reconciliation/run`으로 수동 트리거할 수도 있습니다. **불일치를 발견해도 자동으로 고치지 않습니다** — 불일치 자체가 버그의 신호인데 조용히 덮어쓰면 그 신호를 영영 놓칩니다. 대신 `BalanceDiscrepancy`에 기록을 남겨 사람이 조사하게 합니다. 이유 전체는 **[docs/balance-reconciliation.md](docs/balance-reconciliation.md)** 참고.

## API 목록

| Method | Path | 설명 | 헤더 |
|---|---|---|---|
| GET | `/health` | 헬스체크 | - |
| POST | `/wallets/{id}/charge` | 충전 | `Idempotency-Key` |
| POST | `/payments` | 결제 (우리 DB만, 비관적 락) | `Idempotency-Key` |
| POST | `/payments/external` | 결제 (PG 승인 포함, S9) | `Idempotency-Key`, `X-Simulate-Failure`(선택) |
| GET | `/payments/external/{id}` | 결제 상태 조회 | - |
| POST | `/payments/external/{id}/reconcile` | 보정 수동 트리거 | - |
| POST | `/payments/{id}/refund` | 환불(전액/부분) | `Idempotency-Key` |
| POST | `/settlements/run?date=&merchantId=` | 정산 수동 트리거 | - |
| GET | `/merchants/{id}/stats?from=&to=` | 가맹점별 기간 매출 | - |
| GET | `/merchants/stats?from=&to=` | 전체 가맹점 기간 매출 | - |
| POST | `/outbox/relay` | 아웃박스 발행 수동 트리거 | - |
| POST | `/wallets/transfer` | 지갑 간 송금 | `Idempotency-Key` |
| POST | `/reconciliation/run` | 잔액 정합성 점검 수동 트리거 | - |

## 실행 방법

```bash
# 1. MySQL + RabbitMQ + fake-pg(가짜 PG) 띄우기
docker compose up -d

# 2. 테스트 (Testcontainers가 MySQL/RabbitMQ를, fake-pg는 인프로세스로 자동 기동해서 검증)
./gradlew test

# 3. 앱 실행 (local 프로파일)
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. 확인
curl http://localhost:8080/health
```

RabbitMQ 관리 UI: http://localhost:15672 (guest/guest)
가짜 PG: http://localhost:9999 (단독 실행: `./gradlew :fake-pg:bootRun`)

CI: `main`/`master`로 push하거나 PR을 열면 GitHub Actions가 `./gradlew test`를 자동 실행합니다(`.github/workflows/ci.yml`) — Testcontainers는 러너에 이미 있는 Docker를 그대로 씁니다.

## 더 읽을거리

- [docs/decisions.md](docs/decisions.md) — ADR 전체 (금액 타입, 멱등키 저장 방식, 수수료율 등)
- [docs/optimistic-vs-pessimistic-lock.md](docs/optimistic-vs-pessimistic-lock.md) — 락 전략 비교
- [docs/benchmark.md](docs/benchmark.md) — k6 부하 테스트로 본 두 락의 실측 차이
- [docs/distributed-consistency.md](docs/distributed-consistency.md) — 외부 PG 연동, 왜 2PC가 아니라 멱등성+상태기계+보정인지
- [docs/outbox-pattern.md](docs/outbox-pattern.md) — `@TransactionalEventListener` vs Transactional Outbox, 메시지 유실 방지
- [docs/deadlock-prevention.md](docs/deadlock-prevention.md) — 송금 데드락 재현·해결, 락 순서 고정과 락 타임아웃
- [docs/balance-reconciliation.md](docs/balance-reconciliation.md) — 잔액 정합성 점검 배치, 왜 자동으로 고치지 않는지
- `git log --oneline` — 단계별(S0~S11) 커밋 히스토리. 특히 `b0acfa9`(버그 재현) → `a9a79a8`(수정), `5c4c23a`(데드락 재현) → `b56604e`(수정) 커밋들의 diff가 이 프로젝트의 핵심입니다.
