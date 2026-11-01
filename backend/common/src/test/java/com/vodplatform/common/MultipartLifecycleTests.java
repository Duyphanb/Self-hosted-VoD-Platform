package com.vodplatform.common;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"auth.tokens.secret=test-only-secret-with-at-least-32-bytes","spring.servlet.multipart.max-file-size=1KB","spring.servlet.multipart.max-request-size=1KB"})
@Import(MultipartLifecycleTests.Endpoint.class)
class MultipartLifecycleTests {
    static final Path spool=Path.of(System.getProperty("java.io.tmpdir"),"vod-multipart-test-"+java.util.UUID.randomUUID());
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(spool);Files.writeString(spool.resolve("upload_stale.tmp"),"stale");
        Files.setLastModifiedTime(spool.resolve("upload_stale.tmp"),java.nio.file.attribute.FileTime.fromMillis(1));
        registry.add("spring.servlet.multipart.location",()->spool.toString());
    }
    @Test void startupSweepSuccessOversizeAndCancelledBodyLeaveNoTempFiles() throws Exception {
        assertEmpty();
        String prefix="--audit\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.mp4\"\r\nContent-Type: video/mp4\r\n\r\n";
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        for(int length:new int[]{20,2000}) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/admin/spool-test"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type","multipart/form-data; boundary=audit")
                    .POST(HttpRequest.BodyPublishers.ofString(prefix+"x".repeat(length)+"\r\n--audit--\r\n")).build();
            var response=client.send(request,HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(length==20 ? 200 : 413);
            assertEmpty();
        }
        try(var socket=new Socket("127.0.0.1",port)) {
            socket.getOutputStream().write(("POST /api/v1/admin/spool-test HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=audit\r\nContent-Length: 900\r\n\r\n"+prefix+"partial").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();socket.shutdownOutput();socket.setSoTimeout(5000);socket.getInputStream().readAllBytes();
        }
        assertEmpty();
    }
    void assertEmpty() throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        do {try(var files=Files.list(spool)){if(files.findAny().isEmpty())return;} Thread.sleep(20);}while(System.nanoTime()<deadline);
        try(var files=Files.list(spool)){assertThat(files.toList()).isEmpty();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Endpoint {
        @Bean org.springframework.context.SmartLifecycle beforeConnector() {
            return new org.springframework.context.SmartLifecycle() {
                boolean running;
                public void start() {
                    if (!Files.isDirectory(spool) || Files.exists(spool.resolve("upload_stale.tmp"))) throw new IllegalStateException("Spool must be prepared before connector starts");
                    running=true;
                }
                public void stop(){running=false;}
                public boolean isRunning(){return running;}
                public int getPhase(){return Integer.MAX_VALUE-2049;}
            };
        }
        @Bean TestController testController(){return new TestController();}
        @Bean TestErrors testErrors(){return new TestErrors();}
        @Bean @Order(0) SecurityFilterChain testOnlySecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/admin/spool-test").csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
        }
    }
    @RestController static class TestController {
        @PostMapping("/api/v1/admin/spool-test") int receive(@RequestPart("file") MultipartFile file) throws Exception {
            try(var input=file.getInputStream()){return input.readAllBytes().length;}
        }
    }
    @RestControllerAdvice static class TestErrors {
        @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
        @ResponseStatus(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
        void tooLarge() { }
    }
}
