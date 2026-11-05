package com.mlbgame.importer;

import java.nio.file.Path;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 마스터 데이터 임포트 실행기. {@code import} 프로필에서만 동작하고 끝나면 프로세스를 종료한다.
 * <pre>
 * ./gradlew bootRun --args="--spring.profiles.active=local,import --spring.main.web-application-type=none"
 * </pre>
 */
@Component
@Profile("import")
class ImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final MlbStatsImporter statsImporter;
    private final MinorLeagueImporter minorImporter;
    private final RatingImporter ratingImporter;
    private final MinorRatingImporter minorRatingImporter;
    private final ConfigurableApplicationContext context;
    private final int season;
    private final Path cacheDir;
    private final long requestIntervalMs;
    private final Set<String> steps;

    ImportRunner(MlbStatsImporter statsImporter, MinorLeagueImporter minorImporter, RatingImporter ratingImporter,
                 MinorRatingImporter minorRatingImporter, ConfigurableApplicationContext context,
                 @Value("${import.season:2026}") int season,
                 @Value("${import.cache-dir:import-cache}") String cacheDir,
                 @Value("${import.request-interval-ms:300}") long requestIntervalMs,
                 @Value("${import.steps:stats,minors,ratings}") Set<String> steps) {
        this.statsImporter = statsImporter;
        this.minorImporter = minorImporter;
        this.ratingImporter = ratingImporter;
        this.minorRatingImporter = minorRatingImporter;
        this.context = context;
        this.season = season;
        this.cacheDir = Path.of(cacheDir);
        this.requestIntervalMs = requestIntervalMs;
        this.steps = steps;
    }

    @Override
    public void run(ApplicationArguments args) {
        CachedHttpClient http = new CachedHttpClient(cacheDir, requestIntervalMs);
        long start = System.nanoTime();
        StringBuilder report = new StringBuilder();

        if (steps.contains("stats")) {
            MlbStatsImporter.Summary s = statsImporter.importAll(http, season);
            report.append(String.format("""
                    팀            %d
                    일정          %d경기 (개막 %s)
                    개막 로스터   %d명
                    선수 저장     %d명
                    타격 기록     %d행
                    투구 기록     %d행
                    팀 시즌 결과  %d팀
                    """, s.teams(), s.games(), s.openingDay(), s.rosterPlayers(), s.players(),
                    s.battingRows(), s.pitchingRows(), s.teamSeasons()));
        }
        if (steps.contains("minors")) {
            MinorLeagueImporter.Summary m = minorImporter.importAll(http, season);
            report.append(String.format("""
                    마이너 팀     %d개 (AAA 개막 %s, AA 개막 %s)
                    마이너 로스터 %d명 (신규 등록 %d명)
                    마이너 기록   타격 %d행 / 투구 %d행
                    """, m.affiliates(), m.aaaOpening(), m.aaOpening(), m.rosterPlayers(), m.newPlayers(),
                    m.battingRows(), m.pitchingRows()));
        }
        if (steps.contains("ratings")) {
            RatingImporter.Summary r = ratingImporter.importRatings(http, season);
            report.append(String.format("""
                    능력치        %d명 (타자 %d / 투수 %d / Statcast 보유 %d)
                    %d 리그 평균  %s
                    """, r.rows(), r.batters(), r.pitchers(), r.withStatcast(), season, r.leagueBaseline()));
            MinorRatingImporter.Summary mr = minorRatingImporter.importRatings(season);
            report.append(String.format("""
                    마이너 능력치 MLB 기반 유지 %d명 / 마이너 기록 환산 %d명 / 레벨 분포 무작위 %d명
                    %s
                    """, mr.keptMlb(), mr.translated(), mr.random(), mr.report()));
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("\n\n===== 임포트 완료 ({}ms, 외부 요청 {}회, 캐시: {}) =====\n{}",
                ms, http.networkRequests(), cacheDir.toAbsolutePath(), report);

        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
