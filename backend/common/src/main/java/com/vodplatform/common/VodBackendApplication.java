package com.vodplatform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.vodplatform")
public class VodBackendApplication {

    private static final Logger log = LoggerFactory.getLogger(VodBackendApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(VodBackendApplication.class, args);
        log.info("VoD backend skeleton started");
    }
}
