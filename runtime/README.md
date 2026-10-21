# 가상 충전기 실행 환경

CSMS는 `D:\LH_EV\start-all.bat`로 별도 실행한다. 이 폴더의 스크립트는 가상 충전기 UI만 제어하며 별도 daemon이나 8900 포트를 열지 않는다.

| 명령 | 동작 |
|---|---|
| 상위 `start-all.bat` / 이 폴더 `start.bat` | UI 127.0.0.1:7088 시작, 중복 기동 방지 |
| 상위 `shutdown-all.bat` / 이 폴더 `stop.bat` | 기록된 UI만 종료 |
| 상위 `restart-all.bat` / 이 폴더 `restart.bat` | UI 재시작 |
| `prepare.ps1` | 빌드된 v_daemon JAR만 bin에 복사, SHA-256 확인 |

실행 JAR은 `bin/v-daemon.jar`, 공통 설정은 **상위 `config/local.properties`**다. `config/environment.psd1`은 UI 포트·Java 실행 파일·준비 제한 시간을 지정한다. 예전 daemon 관련 설정과 `config/vdaemon.local.properties`는 이 실행 경로에서 읽지 않는다.

`state/processes.json`에 PID·생성 시각·JAR·포트를 기록하고 종료 전 소유권을 확인한다. 관련 없는 포트 점유 프로세스나 D의 daemon·web·api·proxy·DB·RabbitMQ를 종료하지 않는다. 기록 파일을 임의로 변경하거나 실행 중 폴더를 옮기지 않는다.

소스 변경 후 상위에서 빌드하고 `shutdown-all.bat` → `runtime/prepare.ps1` → `start-all.bat` 순서로 반영한다. `restart-all.bat`는 빌드 결과를 자동 복사하지 않는다. 기동 오류는 `logs/`, 메시지 기록은 `logs/events/`에 저장한다.

포트 변경은 `start-all.bat -UiPort 7098`처럼 지정할 수 있다. 종료는 기록된 PID/포트를 사용한다. 재시작은 충전기 목록과 연결을 초기화하므로 진행 중 거래를 먼저 정상 종료한다.

기본 충전기 연결은 **wss://127.0.0.1:8091/ocpp16/{chargerId}**, SP3(mTLS)다. 인증서 구성과 검증 결과는 [상위 README](../README.md)를 참고한다.
