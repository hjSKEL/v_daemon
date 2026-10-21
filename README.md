# v_daemon — LH_EV 통합 시험용 가상 충전기

`D:\LH_EV`에서 실제 로컬 CSMS(api-eai, proxy-eai, OCPP daemon, admin-web)를 실행하고, 이 앱이 충전기를 대신해 OCPP 1.6 메시지를 전송한다. admin-web에서 충전기 상태·수신 시각·거래 등을 확인하는 용도다. 충전기당 서로 다른 커넥터의 스케줄을 최대 2개 동시에 실행한다.

현재 기본 구성은 **SP3(mTLS)**다. v_daemon은 가상 충전기 UI만 실행하며 CSMS용 daemon을 별도로 띄우지 않는다. 기존 `C:\AntigravityProject\lh\inter-opr` 대신 `D:\LH_EV`를 사용한다. 기존 폴더의 삭제는 자동 승인 검토에서 정책상 차단되어 폴더가 남아 있으며, 현재 실행에는 사용하지 않는다.

| 구성 | 주소 / 포트 | 실행 위치 |
|---|---|---|
| admin-web | `http://127.0.0.1:7000/evAdmin/login` | `D:\LH_EV\web` |
| OCPP daemon | `wss://127.0.0.1:8091/ocpp16/{chargerId}` | `D:\LH_EV\daemon`, SP3 |
| daemon 헬스체크 | `http://127.0.0.1:8090/checkHealth` | 위 daemon의 HTTP 커넥터 |
| proxy-eai / api-eai | 9091 / 9092 | `D:\LH_EV` |
| 가상 충전기 UI | `http://127.0.0.1:7088` | 이 프로젝트 |
| 공유 DB / RabbitMQ | 3306 / 5672 | 기존 로컬 서비스 |

## 실행과 종료

1. 로컬 DB와 RabbitMQ를 실행한다. **`D:\LH_EV\start-all.bat`**로 관제 서비스를 기동한 뒤 admin-web에 로그인한다.
2. **`C:\AntigravityProject\v_daemon\start-all.bat`**로 가상 충전기 앱을 실행하고 `http://127.0.0.1:7088`에 접속한다.
3. DB에 등록된 충전기 ID(예: `000001-01`)를 추가하고 기본 WSS 주소로 연결한다. SP3는 클라이언트 인증서를 사용하므로 화면의 Basic Auth 비밀번호는 비워도 된다. DB의 충전기 등록 여부는 daemon이 확인한다.
4. 필요하면 DB 사전 검증을 수행한 뒤 메시지 또는 커넥터별 스케줄을 전송한다. admin-web의 충전기 상태·수신 시각을 대조한다.
5. 가상 충전기만 다시 시작하려면 **v_daemon의 `restart-all.bat`**, 종료하려면 **v_daemon의 `shutdown-all.bat`**를 실행한다. 이 명령은 D의 CSMS, DB, RabbitMQ를 종료하지 않는다.
6. 관제 환경도 종료하려면 가상 충전기를 먼저 종료하고 **`D:\LH_EV\shutdown-all.bat`**를 실행한다.

v_daemon의 시작은 중복 기동을 방지한다. 재시작은 메모리의 충전기 목록·연결·거래 연결을 초기화하므로 충전이 끝난 뒤 수행하고 다시 등록·연결한다. 스케줄 중지나 앱 종료가 StopTransaction을 대신하지 않는다.

## 빌드와 실행 파일 반영

JDK 17 이상과 Maven 3.9 이상을 사용한다. 현재 검증 환경은 JDK 17.0.19+10, Maven 3.9.16이다. 이 앱의 빌드에 CSMS daemon 소스 빌드는 필요하지 않다.

```powershell
cd C:\AntigravityProject\v_daemon
.\build.ps1 -JavaHome 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
node --test src/test/js/app.test.cjs
.\shutdown-all.bat
.\runtime\prepare.ps1
.\start-all.bat
```

클린 빌드는 JDK 설정 후 `mvn -B clean package "-Dmaven.repo.local=C:\AntigravityProject\repository"`로 수행한다. Java 테스트는 Maven 빌드에 포함되며 JS 테스트는 Node.js로 별도 실행한다.

`runtime/prepare.ps1`은 **v_daemon JAR만** `target/v-daemon-1.0.0.jar`에서 `runtime/bin/v-daemon.jar`로 복사하고 해시를 확인한다. 실행 중 갱신은 거부한다. `restart-all.bat`는 복사된 JAR을 재실행하며 빌드·복사는 하지 않는다. CSMS의 배포 JAR은 이 명령으로 변경되지 않는다.

`start.ps1`은 개발자가 `target/` JAR을 직접 실행하는 별도 진입점이다. 평상시에는 PID 기록을 사용하는 위 세 배치를 사용한다. [실행 환경 상세](runtime/README.md).

## SP3 인증서와 로컬 설정

저장소를 처음 내려받았다면 `config/example.properties`를 `config/local.properties`로 복사하고 DB·TLS 접속 설정을 입력한다. 로컬 설정, 인증서, 로그, 빌드 결과물과 `verification/`의 환경별 검증 기록은 Git에 포함하지 않는다. 아래 검증 기록 링크는 검증을 수행한 로컬 작업 폴더에서 확인할 수 있다.

모든 일반 실행은 **`config/local.properties`**를 사용한다. `runtime/config/vdaemon.local.properties`는 이전 구성의 파일이며 새 배치에서는 읽지 않는다.

- 클라이언트 인증서: `D:/LH_EV/proxy-eai/cert/TEST20260901-client.p12`.
- v_daemon 서버 신뢰 저장소: `config/certs/lh-ev-server-trust.p12`(현재 로컬 설정에는 절대 경로 지정).
- D daemon의 로컬 서버 인증서: `D:/LH_EV/daemon/cert/local-server.p12`, SAN은 `localhost`, `127.0.0.1`.
- D daemon의 `application.properties`가 로컬 서버 인증서를 적용한다. 기존 SP3 클라이언트 인증 요구와 충전기 인증서 신뢰 저장소는 유지한다. v_daemon도 서버 인증서·호스트 검증을 유지한다.
- 비밀번호는 해당 로컬 properties에만 보관한다. 기존 D 실행 파일·설정 백업은 `D:\LH_EV\backup\before-vdaemon-integration-20261021`에 있다.

이 인증서는 로컬 통합 시험용이다. 서버 인증서를 교체하면 v_daemon의 신뢰 저장소도 맞춰야 한다. TLS 설정은 앱 시작 때 읽으므로 수정 후 v_daemon을 재시작한다. SP3 연결을 위해 daemon을 SP1로 낮출 필요는 없다.

이전 `start-local-daemon.ps1`과 `config/local-daemon.properties`는 SP1/8900 단독 시험용 보조 도구다. D 환경 통합 시험에서는 실행하지 않는다. 새 `runtime/start.bat`, `runtime/restart.bat`, `runtime/stop.bat` 역시 가상 충전기 앱만 제어한다.

## 충전 시각 처리 — 2026-10-21 수정

전송 payload의 `timestamp`는 UTC 밀리초 3자리(`.SSSZ`)로 통일한다. `$now`뿐 아니라 스케줄의 고정 시각·MeterValues·Tariff 내부 시각도 전송 시 정규화한다. 로그 바깥의 `time`은 실제 기록 시각인 UTC이고 DB 시각은 한국 시각이므로 정상적으로 9시간 차이가 난다. 과거 시각 스케줄은 payload 시각과 실제 전송 시각이 다를 수 있다.

CSMS 공통 DateUtils는 RFC3339를 OffsetDateTime으로 해석해 1~9자리 소수 초와 시간대 오프셋을 처리한다. 종전 SSS 파서가 긴 소수부를 밀리초 정수로 해석하던 오류를 수정했다. 보관된 로그와 일치하는 충전 거래 10건·상태 이력 175행을 백업 후 정정했다. v_daemon과 D의 4개 서비스 실행 JAR에 반영했다. daemon/admin-web은 클린 빌드 결과물을 재배포·재시작했고, 네 서비스에 포함된 DateUtils 클래스 해시 일치와 충전기 4대 재연결·실제 메시지 수신 시각을 확인했다. [수정 및 검증 기록](verification/timestamp-fix-20261021.json)을 참고한다.

## 최신 통합 검증 — 2026-10-21

[검증 기록](verification/lh-ev-integration-20261021.json)에 빌드·SP3 연결·배치 수명주기·이관·삭제 결과를 기록한다. 실제 `D:\LH_EV\start-all.bat`로 네 CSMS 서비스를 띄우고, mTLS 가상 충전기의 두 커넥터에서 StatusNotification과 Heartbeat를 전송했다. DB와 로그인한 admin-web 조회에서 두 커넥터의 사용 가능(`CHRS03`) 상태와 수신 시각을 확인했다. 충전 거래는 새로 생성하지 않았다.

서로 다른 두 거래의 동시 실행·독립 중지·오류 분리는 [동시 스케줄 검증](verification/dual-schedule-20261021.json)에 기록돼 있다. 아래 과거 SP1 검증은 이전 구성의 참고 기록이며 현재 기본 실행 절차와 구분한다.

## Faulted 고장 알람 시험

메시지 목록의 **StatusNotification("status":"Faulted")**를 선택하면 `connectorId=0`, `status=Faulted`, `errorCode=OtherError`, `timestamp=$now`, `vendorErrorCode=CT03`을 채운다. 전송 action은 표준 `StatusNotification`이다. 필드명은 `vendorErrorCode`이며 `venderErrorCode`는 잘못된 이름이다.

정상 Faulted는 `{}` 응답 후 연결을 유지하고 admin-web의 고장 알람 경로에서 처리한다. 충전기 자체 알람은 커넥터 상태와 독립적으로 유지하며 정상 상태(`Available`, `NoError`) 통보 시 복구한다. 잘못된 payload에는 CALLERROR(`FormationViolation`, details=`{}`)로 응답하고 다음 메시지도 처리한다. 고장 알람은 admin-web의 알람 조회 주기에 따라 화면에 표시된다.

실제 SP3에서 CT03 알람 생성·로그인 조회·반복 수신 중복 방지·복구와 오류 응답 후 Heartbeat를 확인했다. [수정 및 검증 기록](verification/faulted-fix-20261021.json)을 참고한다.

## StatusNotification의 충전기 자체 상태

`StatusNotification`의 `connectorId=0`은 충전기 자체를 나타낸다. 개별 전송에서 StatusNotification을 고른 뒤 **대상 → 충전기 자체 (connectorId=0)**를 선택하면 기본 payload에 0을 채운다. JSON에 직접 0을 입력해도 동작한다. 상단 충전용 커넥터 선택은 1 이상으로 유지하며 StartTransaction·MeterValues·충전 세션 생성에는 0을 사용할 수 없다.

응답을 받으면 **충전기 자체 (0)** 상태를 커넥터 상태 표 위에 별도로 표시한다. API 상태의 `chargePoint`에 보관하며 `connectors`에 가짜 커넥터 0을 만들거나 커넥터 1·2의 거래 상태를 덮어쓰지 않는다. 커넥터 스케줄 실행 중에도 충전기 자체 상태를 개별 전송할 수 있고, 커넥터별 스케줄 JSON에도 StatusNotification 0을 포함할 수 있다. 스케줄 실행 대상 번호는 계속 1 이상이다.

## 충전기 선택 시 화면 동작

조작 대상 충전기를 선택하면 WebSocket URL의 충전기 ID를 자동으로 채운다. 기존 URL의 서버 주소와 포트는 유지한다. 메시지 개별 전송은 BootNotification 기본 payload로 초기화한다. 선택한 충전기에 실행 중인 스케줄이 없으면 스케줄 연속 전송도 빈 JSON(`[]`)과 기본 입력값으로 초기화하고 커넥터는 1로 돌아간다. 실행 중인 스케줄이 있으면 해당 충전기의 마지막 선택 커넥터와 입력값·JSON을 복원한다. 작성 내용은 현재 브라우저 탭 메모리에 보관하며, 새로고침 등으로 작성 내용이 없으면 서버의 실행 스케줄 JSON을 표시한다(생성 입력값은 기본값). 검증 결과는 지우며 이미 서버에서 실행 중인 스케줄은 계속 진행한다.

송수신 실행기록의 로그 위에 선택한 충전기 ID를 표시한다. 충전기 선택 전에 시작한 스케줄 생성 요청의 응답이 늦게 도착해도 새 화면의 JSON을 덮어쓰지 않는다. [화면 수정 검증](verification/selection-ui-20261021.json), [실행 스케줄 보존 검증](verification/running-schedule-ui-20261021.json)을 참고한다.

## 기본 사용 순서

1. `000001-01`처럼 충전소 ID 6자리와 충전기 ID 2자리를 입력해 가상 충전기를 추가한다.
2. D의 daemon을 실행한 뒤 기본 URL `wss://127.0.0.1:8091/ocpp16/{chargerId}`로 연결한다. `{chargerId}`는 선택한 ID로 바뀌며 SP3 클라이언트 인증서를 사용한다.
3. 실제 데이터 생성 전에는 대상 DB 설정 후 **DB 사전 검증**으로 충전기·카드·요금정책을 확인한다.
4. **개별 전송**에서 메시지 종류를 선택하고 기본 payload를 채워 편집·전송한다. 응답과 오류는 하단 기록에서 확인한다. 상단 안내의 닫기(×) 버튼을 누르면 선택한 충전기의 lastError를 초기화하고 안내를 숨긴다. 송수신 로그와 스케줄 오류 이력은 유지하며, 새 오류가 발생하면 안내를 다시 표시한다.
5. **스케줄 연속 전송**에서 충전 시간·충전량·계량 주기를 지정해 스케줄을 생성한다. 표시된 JSON을 검토·편집한 뒤 실행한다.

연결 요청은 비동기다. 연결 버튼이나 `POST /api/chargers/{chargerId}/connect`의 HTTP 200은 요청 접수이며 인증 성공을 뜻하지 않는다. 화면 연결 상태 또는 `GET /api/chargers/{chargerId}`의 `connection=CONNECTED`를 확인한다. 실패 시 `lastError`에서 daemon 핸드셰이크의 401 등 원인을 확인한다.

연결 직후 BootNotification을 자동 전송하지 않는다. 기존 충전 상태를 초기화할 수 있으므로 개별 전송하거나 세션 생성의 포함 옵션으로 선택한다.
시작 계량값은 Wh이며 DB `TB_CHCS005.CA_ELE_NRG`는 kWh다. 실제 누적 계량값을 확인해 1000배 환산한 값으로 입력한다.

## 지원 메시지

- BootNotification
- Authorize
- StatusNotification
- StartTransaction
- MeterValues
- StopTransaction
- DataTransfer (`messageId=Tariff`)
- Heartbeat

`StartTransaction`의 수락 응답에서 받은 `transactionId`를 커넥터별로 저장해 후속 계량·종료 메시지에 사용한다.
`Authorize`는 OCPP 회원카드 인증 메시지이며, 연결 시 사용하는 Basic Auth와 별개다. **회원카드 idTag**를 입력하고 개별 전송에서 Authorize를 선택해 기본 payload(`{"idTag":"카드번호"}`)를 채운다. idTag는 1~20자의 문자열이어야 한다. 응답은 송수신 기록에서 확인하며, 거절되거나 `idTagInfo.status`가 없는 응답은 실패로 처리한다. Authorize 자체는 커넥터의 거래 상태를 변경하지 않는다.
수락된 BootNotification 응답의 `interval`로 자동 Heartbeat 주기를 설정한다. 연결 화면의 초기 주기가 0이면 Boot 수락 전에는 자동 전송하지 않는다.
서버가 보내는 원격 명령은 1차 범위 밖이며 `NotImplemented` CALLERROR로 응답한다.

## 스레드와 실행 정책

- 가상 충전기마다 `charger-충전기ID` 전용 플랫폼 작업 스레드 1개를 사용한다. 최대 64기.
- 충전기의 연결 이벤트, 메시지 전송, 응답 처리, 스케줄, Heartbeat, 커넥터 상태를 해당 스레드에서 직렬 처리한다.
- HTTP 제어와 비동기 WebSocket I/O용 공용 스레드는 별도로 사용한다. 응답 대기·스케줄 대기 때문에 충전기 작업 스레드를 잠그지 않는다.
- 동일 ID는 앱 안에서 중복 등록할 수 없다. 다른 시뮬레이터나 실제 충전기와 ID가 겹치는지는 실행자가 확인한다.
- 연결 종료 시 스케줄을 중지하고 응답 대기 요청을 실패 처리한다. 재연결은 UI에서 명시적으로 실행한다.
- 충전기당 최대 2개 스케줄을 서로 다른 커넥터에서 동시에 실행한다. 같은 커넥터의 중복 실행과 세 번째 동시 실행은 거부한다. 커넥터별 진행률·오류·중지를 독립 관리하되 WebSocket 연결과 전용 작업 스레드는 충전기별로 공유한다.
- 수동 전송은 선택 커넥터의 스케줄 또는 이전 비-Heartbeat 요청의 응답을 기다리는 동안 제한된다. 다른 커넥터의 스케줄은 계속 실행할 수 있으며, 다른 커넥터의 거래 ID를 지정한 수동 계량·종료 메시지는 거부한다.
- 동일 커넥터의 진행 중 거래를 새 StartTransaction으로 덮어쓰지 않는다. BootNotification은 모든 커넥터의 거래와 다른 스케줄이 종료된 경우에만 전송한다. 다른 스케줄에 아직 실행하지 않은 BootNotification이 있어도 동시 실행을 거부한다. 수락 시 daemon 초기화에 맞춰 메모리의 거래 연결을 해제한다.
- 시간 초과·CALLERROR·거절 응답은 **해당 커넥터 스케줄만** 중단하며 메시지를 자동 재전송하지 않는다. 시간 초과는 daemon의 DB 미반영을 의미하지 않는다. 연결 종료 시에는 두 스케줄을 모두 중지한다.
- 화면의 스케줄 중지는 **선택한 커넥터의 이후 전송만** 중지한다. 이미 전송한 메시지의 응답과 상태 반영은 계속 처리한다. 충전을 종료하려면 해당 커넥터의 StopTransaction을 별도로 전송한다.
- 앱 안에서 재연결할 때 거래 상태를 유지한다. 충전기 제거·앱 재시작 후에는 메모리 상태를 복원하지 않는다. 진행 중 거래는 daemon 상태를 확인해 명시적인 ID로 종료할 수 있다.
- 현재 daemon의 충전 ID가 서버 시각 초 단위로 생성되므로, 두 스케줄의 StartTransaction은 앞 요청의 응답을 기다리고 최소 1.1초 간격으로 자동 조정한다. 수동 StartTransaction의 간격 위반은 오류로 거부한다. 이는 동시 실행 스케줄의 실패가 아니라 충전 시작 단계의 대기다.

## 스케줄과 과거 데이터

스케줄은 `[{"delayMs":0,"action":"Heartbeat","payload":{}}]` 형태다.
`delayMs`는 **이전 단계의 정상 응답을 받은 후** 대기할 시간이다. 세션 생성은 Available → [Authorize] → Tariff → Preparing → StartTransaction → Charging → MeterValues 반복 → StopTransaction → Finishing → Available을 만든다. **충전 시작 전에 Authorize 포함**을 선택하면 Available 다음에 Authorize가 들어간다. 인증 거절 시 이후 충전 시작 단계로 진행하지 않는다.
세션 앞 Boot는 선택 사항이며, 앱에서 자동 Heartbeat는 병행한다. 두 커넥터를 동시에 실행할 때는 Boot 포함을 해제하고, 필요하면 두 스케줄 실행 전에 Boot를 한 번 별도 전송한다.

### 두 커넥터를 동시에 실행하는 순서

1. 같은 충전기에서 커넥터 **1**을 선택하고 카드·계량값·시간을 입력해 스케줄을 생성·실행한다.
2. 커넥터를 **2**로 바꾼 뒤 해당 커넥터의 카드·시작 계량값으로 별도 생성·실행한다. 같은 충전기 안에서 커넥터를 전환하면 스케줄 JSON과 생성 입력값을 커넥터별로 보관하고 복원한다. 조작 대상 충전기를 선택하면 실행 중인 스케줄이 있는 충전기는 작성 내용을 복원하고, 없는 충전기는 입력과 JSON을 초기화한다. 생성 입력값은 현재 브라우저 탭에만 보관된다.
3. **커넥터별 스케줄** 표에서 두 진행률·오류를 확인한다. 실행·중지 버튼은 위에서 선택한 커넥터에만 적용된다.

각 스케줄에는 선택한 커넥터의 메시지를 넣는다. 충전기 자체를 알리는 StatusNotification(connectorId=0)은 함께 넣을 수 있다. 다른 커넥터의 세션을 하나의 JSON에 이어 붙이지 않는다. `$transactionId`는 실행 중인 스케줄의 커넥터를 기준으로 치환하며, `$transactionId:2`처럼 명시할 때도 해당 스케줄의 커넥터와 일치해야 한다. StopTransaction에는 OCPP 표준에 없는 `connectorId`를 추가하지 않는다.

HTTP 제어에서 `POST /api/chargers/{id}/schedule`은 `{"connectorId":2,"steps":[...]}`, 선택 중지는 `/stop`에 `{"connectorId":2}`를 보낸다. `/send`도 `connectorId`로 조작 대상을 지정할 수 있다. 생략된 스케줄 대상은 1이고, 기존 API 호환을 위해 `/stop`의 빈 객체는 전체 스케줄 중지다. 상태 응답의 `schedules` 배열은 커넥터별 `status/step/totalSteps/lastError/pending`이며 상위 `schedule/step/totalSteps`는 충전기 전체 요약이다.

**스케줄 생성 / 아래에 표시**를 누르기 전에 위의 **회원카드 idTag**를 입력한다. 이 버튼은 daemon 연결 없이 JSON만 생성하며 실제 전송은 **스케줄 실행**을 눌러야 시작한다. 생성 결과나 입력 오류는 버튼 아래에 표시되고, 생성 성공 시 스케줄 JSON 입력란으로 이동한다. 생성은 기존 JSON을 교체하고 **기존 스케줄 뒤에 세션 추가**는 뒤에 덧붙인다. 입력·서버 오류가 발생하면 기존 JSON을 유지한다.

- 기준 시각을 비우고 배속 1로 생성하면 `$now`가 전송 시점 UTC 시각으로 바뀐다.
- ISO 8601 기준 시각을 입력하면 해당 시각부터 진행하는 과거 timestamp를 만든다. 배속은 실제 대기 시간을 줄이고 메시지 timestamp의 간격은 유지한다.
- 화면의 **기존 스케줄 뒤에 세션 추가**로 여러 세션·커넥터를 이어 실행할 수 있다. 후속 세션의 시작 계량값·기준 시각은 직접 지정한다.
- 한 실행은 최대 2000단계, 생성 세션은 최대 1000개 계량 메시지다.
- `$transactionId`는 payload의 connectorId(없으면 1)의 거래 ID, `$transactionId:2`는 커넥터 2의 거래 ID다. StopTransaction에는 표준에 없는 connectorId를 추가하지 않는다.
- Tariff의 `data`는 JSON 객체를 인코딩한 문자열이며 그 안의 `$now`도 치환한다.

과거 timestamp만으로 DB의 모든 시각을 과거로 만들 수는 없다. 현재 daemon의 충전 ID·수정 시각·Boot/Heartbeat 수집 시각은 서버 현재 시각을 사용한다.
충전 시작·종료와 계량 시각 등은 메시지 timestamp를 반영한다. 따라서 생성 후 실제 DB 결과를 확인해야 한다.

## DB 조회 설정

`config/example.properties`를 `config/local.properties`로 복사하고 **대상 DB를 확인한 후** 접속정보를 입력한다.
Windows 경로는 properties 파일에서 `C:/path/file.p12`처럼 슬래시로 적는다. 접속정보·인증서는 Git 제외 대상이다.

위 파일은 `start.ps1`과 새 start-all/restart-all 배치가 공통으로 읽는다. DB 조회는 선택 기능이므로 계정이 비어 있어도 UI는 기동되지만 DB 사전 검증에는 접속정보가 필요하다. 계정은 한 번만 지정하면 재시작 후에도 유지된다.

```properties
db.url=jdbc:mysql://127.0.0.1:3306/LOCAL_CSMS?serverTimezone=Asia/Seoul
db.username=조회용계정
db.password=직접입력
```

조회하는 데이터:

| 항목 | 테이블 / 연결 |
|---|---|
| 충전소 | `TB_CHCP001.CP_ID` |
| 충전기·사용 여부·LH 연동 ID | `TB_CHCS001` |
| 부팅 정보 갱신 대상 | `TB_CHCS002` |
| 커넥터별 현재 상태·시작 계량값 | `TB_CHCS005` |
| 회원카드·정지·삭제 여부 | `TB_CUCU002` |
| 충전기별 요금정책 | `TB_CHCS001.PRD_TP → TB_PDPD001.ID` |
| 적용 기간과 단가 | `TB_PDPD002.PROD_TP LIKE CONCAT(충전기 PRD_TP, '%')`, `STRT_DT ≤ 기준일 < END_DT` |

기준일은 입력 시각을 한국 시간으로 변환한 날짜다. 연결은 read-only로 열고 파라미터화된 SELECT만 사용한다.
daemon과 같은 스키마인지 확인한다. 기간에 맞는 요금이 없으면 검증 실패로 표시한다(daemon은 이 경우 첫 요금으로 대체할 수 있다).
검증은 조작자가 실행하는 독립 기능이며 연결·개별 메시지·스케줄을 강제로 차단하지 않는다. 미등록·거절 테스트 및 DB 없는 모의 시험도 가능하다.

DB 복사·신규 기초 데이터 생성은 이번 범위에 포함하지 않았다. 이 앱은 기존 `backup-db-copy`의 데이터 복제 기능까지 대체하지 않는다.

## 인증과 설정

Basic Auth 비밀번호는 연결 화면에 입력한다. 저장하거나 로그에 남기지 않는다.
보조 SP1/2 시험을 할 때의 사용자 이름은 선택한 충전기 ID(예: `000001-01`), 비밀번호는 `TB_CHCS001.CS_PWD`다. 기본 SP3에서는 Basic Auth 대신 클라이언트 인증서를 사용한다. daemon의 기존 검증 로직을 사용하며 누락·불일치 시 HTTP 401로 연결을 거부한다. 다른 PC와 통신하는 평문 접속 용도로 사용하지 않는다.

WSS는 기본 서버 인증서·호스트 검증을 유지한다. mTLS를 쓸 때 로컬 설정 파일에 클라이언트 키 저장소와 신뢰 저장소를 지정한다.

기존 daemon을 SP3로 실행해 연결할 때는 mTLS 설정이 필수다. 실제 포트와 TLS 사용 여부는 daemon 실행 인자로 결정된다. `config/local.properties`의 `tls.keyStore`에는 daemon이 신뢰하는 클라이언트 인증서와 개인키가 들어 있는 저장소를, `tls.trustStore`에는 daemon 서버 인증서의 발급 CA를 신뢰하는 저장소를 지정한다. URL의 호스트는 서버 인증서의 SAN과 일치해야 한다. `localhost`가 SAN에 없다면 인증서에 포함된 호스트로 URL을 바꾼다. 기존 daemon의 8090은 HTTP 상태 확인용 포트이므로 WebSocket 연결에 사용하지 않는다.

```properties
tls.keyStore=C:/certs/client.p12
tls.keyStoreType=PKCS12
tls.keyStorePassword=직접입력
tls.trustStore=C:/certs/truststore.p12
tls.trustStoreType=PKCS12
tls.trustStorePassword=직접입력
```

TLS 설정은 앱 시작 시 읽으므로 설정 변경 후 v_daemon을 다시 시작한다. Basic Auth 비밀번호 입력만으로 mTLS 인증서를 대신할 수 없다.

설정 파일은 Java `Properties.load(InputStream)`으로 읽는다. 비 ASCII 문자를 설정값에 사용할 때는 `\uXXXX` 이스케이프를 사용한다. Basic Auth 비밀번호는 JSON 요청으로 전달하므로 이 파일 인코딩 규칙과 별개다.

연결 실패 시 화면과 기록에는 내부 예외 종류·메시지·원인 경로가 표시된다. `ConnectException`은 대상 주소·포트와 daemon 실행 여부를, `SSLHandshakeException`은 인증서·신뢰 저장소·호스트 일치를 확인한다. `WebSocketHandshakeException`에는 HTTP 상태 코드가 함께 표시된다.

모든 설정은 `VDAEMON_` 환경변수로 덮어쓸 수 있다. 예: `db.password → VDAEMON_DB_PASSWORD`, `tls.keyStorePassword → VDAEMON_TLS_KEYSTOREPASSWORD`.
`server.port` 기본 7088, 응답 제한 `request.timeout.seconds` 기본 15초, 기록 위치 `logs.directory` 기본 `logs`.
웹 서버는 127.0.0.1에만 바인딩하고 제어 요청은 같은 Origin의 JSON 요청으로 제한한다.

## 기록과 검증

`logs/충전기ID.jsonl`에 송신·수신 원문, 시각, 이벤트, 오류를 기록한다. 파일당 약 5MB에서 순환하며 이전 파일 3개를 유지한다.
UI는 최근 200개를 표시·다운로드한다. 기록에는 테스트 카드번호 등 payload가 포함되므로 로컬에 보관한다.
전송 수락과 실제 daemon DB 처리는 다를 수 있다. 특히 현재 daemon은 StartTransaction 인증 거절에서도 저장 로직을 시도할 수 있다.

`build.ps1`은 다음을 자동 검증한다: 메시지 순서·거래 ID 연결·커넥터 격리, 전용 작업 스레드, 충전기 간 독립성, 시간 초과 시 무재전송, 거절/CALLERROR 중단, 중지 후 응답 처리, Heartbeat 병행, HTTP 제어 출처 검증, 세션 생성의 계량값·시각·입력 범위.
Authorize의 개별·스케줄 전송, 거절·잘못된 응답 시 중단, 스케줄 생성 HTTP API도 자동 테스트한다. 스케줄 생성 버튼의 입력 오류 표시·JSON 교체·추가·기존 내용 보존과 Authorize payload는 Node.js가 있는 환경에서 `node --test src/test/js/app.test.cjs`로 별도 확인한다. 이 UI 테스트는 DOM과 API 응답을 모의하며 실제 브라우저 표시나 daemon으로의 메시지 전송을 검증하지 않는다.
현재 D 환경의 mTLS 연결과 메시지 수신 검증은 위 통합 검증 기록을 참조한다. 아래는 이전 SP1 구성의 검증 기록이다.

### 실제 실행·연결 검증 — 2026-10-21

아래 표는 동시 스케줄 변경 **이전** 버전의 로컬 DB/SP1 연결 검증이다. 동시 스케줄 버전은 별도 기록 `verification/dual-schedule-20261021.json`에서 빌드·독립 중지·오류 분리·거래 ID 분리·실행 파일 반영 여부를 확인한다. 실제 DB에 두 충전 거래를 생성하는 시험은 수행하지 않았다.

현재 소스로 `build.ps1` 및 `mvn clean package`를 실행하고, 별도 빌드한 LocalCSMS daemon과 함께 `runtime/prepare.ps1`로 반영했다. 새 JAR과 runtime JAR의 SHA-256 일치를 확인했다. 기본 **UI 7088 / SP1 daemon 8900**과 로컬 DB의 등록 충전기 `000001-01`을 사용했다.

| 검증 항목 | 결과 |
|---|---|
| Java/JS 테스트 | Java **21건**, JS **6건** 모두 통과. daemon 의존 모듈 패키징은 테스트를 생략해 별도로 수행 |
| 실제 기동 | 7088·8900만 루프백 수신, 8090 추가 커넥터 없음. daemon `status=OK`, `mqConnected=true` |
| UI 리소스·요청 제한 | `/`, `/app.js`, `/style.css` HTTP 200. Origin 없는 제어 요청 403, 충전기 ID 중복 등록 400 |
| DB 사전 검증 | 로컬 DB 읽기 전용 조회 7개 항목 통과: 충전소·충전기·부가정보·커넥터 2개·회원카드·요금정책·적용 단가 |
| 스케줄 생성 | Authorize 포함 **15단계** JSON 생성, 잘못된 입력 400. 스케줄 실행은 하지 않음 |
| 정상 비밀번호 | 비동기 상태 `CONNECTED`, daemon 세션 목록 및 `TB_SYDA001`의 `127.0.0.1:8900` 확인 |
| 비밀번호 누락·오류 | daemon 핸드셰이크 401, 앱 상태 `DISCONNECTED` 및 원인 표시 |
| 재시작·종료 | 이미 실행 중인 Start는 중복 기동하지 않음. Restart에서 두 JVM 교체·상태 확인, Stop 후 7088·8900 LISTEN 해제 |
| 데이터·설정 보존 | OCPP 송신 0건, `TB_CHCS005` 상태 해시 동일, 접속 경로 검증 전 값과 동일, 기존 runtime 로컬 설정 해시 동일. DB·RabbitMQ는 계속 실행 |

DB 조회 계정은 검증 프로세스에만 환경변수로 전달했으며 설정 파일이나 기록에 비밀번호를 추가하지 않았다. 앱 설정 파일에 조회 계정이 없으면 다음 실행에서 DB 사전 검증용 계정을 별도로 지정해야 한다.

상세 결과는 [verification/readme-20261021.json](verification/readme-20261021.json), 로컬 연결 검증과 재현 스크립트는 `logs/readme-verification-20261021/`에 보관한다. HTTP API·리소스와 SP1 연결을 확인한 범위이며 실제 브라우저 클릭, 실제 충전 세션 처리, 스케줄 송신, 원격 명령 및 mTLS 연동을 검증한 결과는 아니다.

`Transport.Link.close()`는 `WebSocket.abort()`로 연결을 즉시 끊는다. 따라서 사용자 연결 종료 시 daemon에 `EOFException`과 종료 코드 `1006`이 기록될 수 있으며 Basic Auth 인증 실패와 구분한다. 이전 2026-10-20의 별도 7089 인스턴스 기록은 `logs/connection-check-20261020-153058/connection-result.json`에 남아 있으며 오늘의 기본 포트 검증과 구분한다.
