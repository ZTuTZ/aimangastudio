package com.aimanga.v2;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@MapperScan("com.aimanga.v2.repository")
public class AimangaV2Application {

    public static void main(String[] args) {
        SpringApplication.run(AimangaV2Application.class, args);
    }
}
