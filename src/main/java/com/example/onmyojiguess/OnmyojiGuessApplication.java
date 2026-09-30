package com.example.onmyojiguess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class OnmyojiGuessApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnmyojiGuessApplication.class, args);
    }

}
