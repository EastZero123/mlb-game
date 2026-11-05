package com.mlbgame.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 외부 데이터 다운로드. 받은 원본은 디스크에 캐시해서 재실행 시 외부 요청을 하지 않는다.
 * 요청 사이에 최소 간격을 두고, 429/5xx 는 지수 백오프로 재시도한다.
 */
final class CachedHttpClient {

    private static final Logger log = LoggerFactory.getLogger(CachedHttpClient.class);
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Path cacheDir;
    private final long minIntervalMillis;
    private long lastRequestAt;
    private int networkRequests;

    CachedHttpClient(Path cacheDir, long minIntervalMillis) {
        this.cacheDir = cacheDir;
        this.minIntervalMillis = minIntervalMillis;
    }

    /**
     * @param cacheKey 캐시 파일 상대 경로 (예: {@code statsapi/teams-2026.json})
     */
    String get(String cacheKey, String url, String accept) {
        Path file = cacheDir.resolve(cacheKey);
        try {
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            String body = download(url, accept);
            Files.createDirectories(file.getParent());
            Files.writeString(file, body, StandardCharsets.UTF_8);
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int networkRequests() {
        return networkRequests;
    }

    private String download(String url, String accept) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", accept)
                .header("User-Agent", "mlb-game-importer/0.1 (personal non-commercial)")
                .GET()
                .build();

        for (int attempt = 1; ; attempt++) {
            throttle();
            networkRequests++;
            try {
                HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = res.statusCode();
                if (code == 200) {
                    log.debug("GET {} ({}KB)", url, res.body().length() / 1024);
                    return res.body();
                }
                if ((code == 429 || code >= 500) && attempt < MAX_ATTEMPTS) {
                    backoff(attempt, "HTTP " + code);
                    continue;
                }
                throw new IOException("HTTP " + code + " : " + url);
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                backoff(attempt, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }

    private void throttle() {
        long wait = lastRequestAt + minIntervalMillis - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private void backoff(int attempt, String reason) {
        long millis = 1000L << attempt;
        log.warn("재시도 {}/{} ({}ms 후): {}", attempt, MAX_ATTEMPTS, millis, reason);
        sleep(millis);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
