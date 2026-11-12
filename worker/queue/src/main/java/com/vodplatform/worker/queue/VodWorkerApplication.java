package com.vodplatform.worker.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.vodplatform.worker")
public class VodWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VodWorkerApplication.class, args);
    }
}
