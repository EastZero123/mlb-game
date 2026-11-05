# 운영 점검 (부하·로그·지표)

저사양 서버에서 여러 유저가 동시에 쓸 때 어디서 느려지는지 보기 위한 장치. 코드 쪽은 기본으로 켜져 있고, 서버 설정은 직접 적용한다.

## 1. 앱 로그 (로거 이름으로 구분)

| 로거 | 형식 (한 줄) | 언제 |
|---|---|---|
| `http` | `GET /careers/{id}/teams/{teamId} 200 38ms htmx` | 모든 요청 (정적 파일 제외). 500ms 이상은 WARN `slow` |
| `ops` | `op=day career=12 ms=41 sim=18 db=19 load=0 games=15 minor=30 rows=820` | 무거운 작업마다. 1000ms 이상은 WARN `slow` |
| `ops` | `op=day career=12 conflict` | 같은 커리어를 동시에 눌러 한쪽이 실패 |
| `stats` | `careers=7/32 hit=0.97 loads=12 evict=3 loadAvg=820ms heap=312/768MB db=1/6 wait=0` | 5분마다 (웹 서버 실행 때만) |

`ops` 작업 이름: `day`(다음 날), `stage`(오프시즌 단계), `fa-round`(FA 라운드), `trade`(트레이드 제안), `load`(커리어 메모리 적재).

`op=day` 의 저장 단계별 시간(ms): `dbGames`(경기·순위), `dbBat`(타격 기록), `dbPit`(투구 기록), `dbPlayers`(선수 상태·시리즈·대체 선수).
`minorRows` 는 그날 함께 저장한 마이너 기록 행 수, `flush=true` 는 모아 둔 마이너 기록을 저장한 날.

### 마이너 기록 모아 저장 (`season.PendingStats`)
- MLB 시즌 기록은 매일 저장, 마이너(AAA·AA) 기록은 메모리에 합산해 7일마다·단계가 바뀌는 날·커리어가 캐시에서 내려갈 때·서버 종료 때 저장
  (`op=flush` 로그). 하루 저장 행이 약 1/3 로 줄어든다.
- 정상 종료(`kill`, graceful)에서는 남김없이 저장된다. 서버가 강제로 죽으면(`kill -9`, 전원) 최대 6일치 마이너 기록만 빠질 수 있다.

판단 기준:
- `stats` 의 `careers` 가 상한에 붙고 `evict` 가 계속 늘면: 메모리 캐시 상한(`game.cache.max-careers`)이 동시 사용자보다 작음 → 다시 적재(`op=load`)가 잦아짐
- `wait` 가 0 보다 크면: DB 커넥션 풀이 모자람 (`DB_POOL_MAX`)
- `heap` 이 최대에 가깝고 GC 로그에 긴 정지가 보이면: 힙(-Xmx) 또는 캐시 상한 조정
- `op=day` 의 `db` 가 `sim` 보다 크게 늘면: DB 쪽 병목 (느린 쿼리 로그 확인)

설정 (application-*.yml, `app.ops.*`):

```yaml
app:
  ops:
    request-log: true        # false = 느린 요청만 기록
    slow-request-ms: 500
    slow-ms: 1000            # ops 작업 느림 기준
    stats-interval: PT5M     # stats 주기
```

## 2. 지표 (Actuator, 관리 포트 127.0.0.1 전용: 로컬 8081 / 운영 8091)

외부에 열리지 않는다 (nginx 에도 넣지 않음). 서버 안에서 (운영은 8091):

```bash
curl -s localhost:8091/actuator/health
curl -s "localhost:8091/actuator/metrics/game.op?tag=op:day"
curl -s localhost:8091/actuator/metrics/game.conflict
curl -s localhost:8091/actuator/metrics/game.cache.careers
curl -s localhost:8091/actuator/metrics/http.server.requests
curl -s localhost:8091/actuator/metrics/hikaricp.connections.pending
curl -s localhost:8091/actuator/metrics/jvm.memory.used
```

`MANAGEMENT_PORT` 를 앱 포트와 같게 바꾸면 지표가 외부에 열리므로 바꾸지 않는다.

## 3. 동시 부하 벤치마크 (서버 안에서, HTTP 없이)

커리어 N개를 만들어 스레드 T개가 동시에 "다음 날"을 D일씩 반복. 끝나면 만든 커리어를 삭제한다.

```bash
./gradlew bootRun --args="--spring.profiles.active=local,load-bench --spring.main.web-application-type=none --bench.careers=8 --bench.threads=8 --bench.days=30"
```

출력: 커리어 적재 시간·커리어당 메모리, 초당 처리 일수, 하루 처리 시간(중앙·95%·최대), 최대 힙, DB 커넥션 최대 사용·대기.
운영 서버와 같은 힙(-Xmx)으로 돌려야 의미가 있다 (`JAVA_OPTS` 또는 Gradle `bootRun` 의 `jvmArgs`).

운영 서버에서는 jar 로 (웹 포트를 열지 않으므로 본 서비스와 충돌 없음, 접속자가 없는 시간에, 실행 전 DB 백업 권장):

```bash
java -Xmx768m -jar mlb-game.jar --spring.profiles.active=prod,load-bench --spring.main.web-application-type=none --bench.careers=8 --bench.threads=8 --bench.days=30
```

벤치마크 커리어는 사용자 ID 9억 번대로 만들고 끝나면 삭제한다 (중간에 끊겨도 다음 실행 때 먼저 정리).

## 4. 배포 (같은 서버에 기존 서비스 /game 과 공존)

| 서비스 | 앱 포트 | 관리 포트 | nginx 경로 |
|---|---|---|---|
| 기존 서비스 (context path /game) | 8080 | - | `/game` |
| MLB 매니저 (이 프로젝트) | **8090** (`SERVER_PORT`) | **8091** (`MANAGEMENT_PORT`, 127.0.0.1) | `/` |

- 운영 포트는 `application-prod.yml` (`server.port: ${SERVER_PORT:8090}`, `management.server.port: ${MANAGEMENT_PORT:8091}`)
  과 systemd 환경변수 양쪽에 있다 (환경변수가 우선). git 제외 파일이라 서버에 직접 둔다:
  `/opt/mlb-game/config/application-prod.yml` 에 두면 jar 안의 설정보다 우선한다.
- nginx: [`deploy/nginx/mlb-162.com.conf`](../deploy/nginx/mlb-162.com.conf). `/game` 은 경로 그대로 8080 으로,
  나머지(`/`)는 8090 으로. 정적 파일(`/webjars/`, `/css/`)은 브라우저 캐시. 관리 포트는 넣지 않음.
  ```bash
  sudo cp deploy/nginx/mlb-162.com.conf /etc/nginx/sites-available/mlb-162.com   # 서버의 기존 파일 위치에 맞게
  sudo nginx -t && sudo systemctl reload nginx
  ```
- systemd: [`deploy/systemd/mlb-game.service`](../deploy/systemd/mlb-game.service) (사용자 `mlbgame`, `/opt/mlb-game`,
  JDK `/opt/jdk-21` 은 서버에 맞게 수정). 힙 768MB·SerialGC·GC 로그 포함.
  ```bash
  sudo cp deploy/systemd/mlb-game.service /etc/systemd/system/
  sudo systemctl daemon-reload && sudo systemctl enable --now mlb-game
  journalctl -u mlb-game -f
  ```
- 세션 쿠키 이름은 `MLBSID` 라 기존 서비스(`/game`)의 세션 쿠키와 겹치지 않는다.

## 5. 서버 설정 (직접 적용)

MySQL 느린 쿼리 로그 (`my.cnf` 의 `[mysqld]`):

```ini
slow_query_log = 1
slow_query_log_file = /var/log/mysql/slow.log
long_query_time = 0.3
```

JVM GC 로그 (실행 옵션):

```bash
-Xlog:gc*:file=logs/gc.log:time,uptime:filecount=5,filesize=10m
```

앱 로그 파일·롤링 (application-prod.yml):

```yaml
logging:
  file:
    name: logs/mlb-game.log
  logback:
    rollingpolicy:
      max-file-size: 20MB
      max-history: 14
      total-size-cap: 500MB
```
