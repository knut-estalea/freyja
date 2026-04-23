package com.impact.freyja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RingProperties.class)
class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

}
