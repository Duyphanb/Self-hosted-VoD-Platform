package com.vodplatform.worker.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication(scanBasePackages = "com.vodplatform.worker")
public class VodWorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(VodWorkerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(VodWorkerApplication.class, args);
    }

    @Bean
    ApplicationRunner workerStartupLogger(Environment environment) {
        return new WorkerStartupLogger(environment);
    }

    private record WorkerStartupLogger(Environment environment) implements ApplicationRunner {

        @Override
        public void run(ApplicationArguments args) {
            log.info(
                    "VoD worker skeleton initialized; queue consumption disabled; ffmpegPath={}; ffprobePath={}",
                    environment.getProperty("FFMPEG_PATH", "ffmpeg"),
                    environment.getProperty("FFPROBE_PATH", "ffprobe")
            );
        }
    }
}
