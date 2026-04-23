package com.impact.freyja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RingProperties.class)
class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

}
