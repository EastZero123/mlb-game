# mlb-game

야구판 Football Manager 스타일 웹게임 (Spring Boot 4 / Java 21 / MySQL 8.0).

- 설계·최적화 원칙: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 로컬 실행

사전 준비: JDK 21, MySQL 8.0.46 (바이너리 설치)

MySQL 설정은 [config/mysql/mlbgame.cnf](config/mysql/mlbgame.cnf) 를 `my.cnf` 에 반영한 뒤 재시작.

DB / 계정 생성 (root 로 접속해서 1회 실행):

```sql
CREATE DATABASE mlbgame CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'mlbgame'@'localhost' IDENTIFIED BY 'mlbgame';
GRANT ALL PRIVILEGES ON mlbgame.* TO 'mlbgame'@'localhost';
```

운영에서는 비밀번호를 바꾸고 환경변수(`DB_PASSWORD`)로 주입.

```bash
./gradlew bootRun
```

- 앱: http://localhost:8080 → 가입 → 로그인 → 커리어 슬롯에서 팀 선택 → 대시보드의 "다음 날"
- 헬스체크: http://127.0.0.1:8081/actuator/health
- 화면 템플릿을 고치면서 볼 때는 `application-local.yml` 에 `spring.thymeleaf.cache: false` 를 두면 재시작 없이 반영된다

## 마스터 데이터 임포트

1. `stats`: MLB Stats API → 팀·2026 일정·개막 로스터·선수·연도별 기록
2. `minors`: AAA·AA 팀(모기업 연결)·레벨별 개막 로스터·마이너 선수·최근 3시즌 마이너 기록
3. `ratings`: Baseball Savant(Statcast) + 기록 → `mst_player_rating` 능력치,
   이어서 MLB 기록이 부족한 선수는 마이너 기록 환산 → 능력치 역산 (기록도 부족하면 레벨 분포 무작위)

마이너 능력치 역산은 검증기가 저장한 계수(`mst_league.rate_coef`)를 쓰므로, 검증기를 한 번 이상 돌린 뒤 실행하는 게 정확하다.

원본은 `import-cache/` 에 저장되어 재실행 시 외부 요청 없이 캐시를 사용한다 (처음부터 다시 받으려면 폴더 삭제).

```bash
./gradlew bootRun --args="--spring.profiles.active=local,import --spring.main.web-application-type=none"
```

능력치만 다시 계산: 위 명령의 args 에 `--import.steps=ratings` 추가.

## 커리어 생성 벤치마크

커리어를 반복 생성·삭제하며 시간을 재고, 샘플링·개막 배치·연봉 분포를 출력한다 (기본 10회, `--bench.count=N`).

```bash
./gradlew bootRun --args="--spring.profiles.active=local,career-bench --spring.main.web-application-type=none"
```

## 시즌 진행 벤치마크

커리어 1개로 (정규시즌 → 포스트시즌 → 오프시즌) 을 N 시즌 반복하고 (기본 2, `--bench.seasons=N`),
하루 처리 시간·저장량·레벨별 리그 기록·오프시즌 결과를 출력한다. 끝나면 커리어를 삭제한다 (`--bench.keep=true` 면 남김).

```bash
./gradlew bootRun --args="--spring.profiles.active=local,season-bench --spring.main.web-application-type=none"
```

## 2026 재현 검증

능력치 + 개막 로스터 + 실제 일정으로 시즌을 반복 시뮬레이션해 실제 2026 결과와 비교 (기본 20회).

```bash
./gradlew bootRun --args="--spring.profiles.active=local,validate --spring.main.web-application-type=none"
```

## 빌드 / 테스트

```bash
./gradlew clean bootJar
./gradlew test
./gradlew benchmark   # 시뮬레이션 성능 측정 (test 에서는 제외됨)
```

## 운영 실행 예시

```bash
SPRING_PROFILES_ACTIVE=prod DB_HOST=... DB_USER=... DB_PASSWORD=... \
java -Xms512m -Xmx512m -XX:+UseSerialGC -jar build/libs/mlb-game-0.0.1-SNAPSHOT.jar
```
