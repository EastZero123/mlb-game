package com.mlbgame;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class MlbGameApplication {

    public static void main(String[] args) {
        SpringApplication.run(MlbGameApplication.class, args);
    }
}
