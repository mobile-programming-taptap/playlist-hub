package com.mptaptap.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class TapTapApplication {
    public static void main(String[] args) { SpringApplication.run(TapTapApplication.class, args); }
}
