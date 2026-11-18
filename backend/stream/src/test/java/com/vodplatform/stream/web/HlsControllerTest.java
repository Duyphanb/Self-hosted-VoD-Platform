package com.vodplatform.stream.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vodplatform.playback.service.HlsAccess;
import com.vodplatform.playback.service.HlsAccessService;
import com.vodplatform.shared.storage.ObjectStorage;
import com.vodplatform.shared.storage.StoredObject;
import com.vodplatform.stream.exception.HlsStorageUnavailableException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Exercises partial-copy and disconnect paths without buffering a full media object. */
class HlsControllerTest {
    private final UUID id = UUID.randomUUID();
    private final HlsAccessService access = mock(HlsAccessService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);

    private HlsController controller(InputStream stream, long size) throws Exception {
        when(access.requireAccess(any())).thenReturn(new HlsAccess("vod-hls", "master-key", "attempt-prefix/"));
        when(storage.get(anyString(), anyString())).thenReturn(new StoredObject(stream, size));
        return new HlsController(access, storage);
    }

    @Test
    void clientWriteFailureClosesStorageWithoutAnErrorBody() throws Exception {
        var input = new FailingInput(false);
        var response = new MockHttpServletResponse() {
            @Override public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override public void write(int value) throws IOException { throw new IOException("client disconnected"); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) { }
                };
            }
        };
        controller(input, 32768).playlist(id.toString(), response);
        assertThat(input.closed).isTrue();
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void firstReadFailureCanStillBecome503AndClosesStorage() throws Exception {
        var input = new FailingInput(true);
        var response = new MockHttpServletResponse();
        var controller = controller(input, 32768);
        assertThatThrownBy(() -> controller.playlist(id.toString(), response))
                .isInstanceOf(HlsStorageUnavailableException.class);
        assertThat(input.closed).isTrue();
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getContentType()).isNull();
    }

    @Test
    void readFailureDiscardsUncommittedMediaBefore503() throws Exception {
        var input = new FailingInput(false);
        var response = new MockHttpServletResponse();
        response.setBufferSize(65536);
        var controller = controller(input, 32768);
        assertThatThrownBy(() -> controller.playlist(id.toString(), response))
                .isInstanceOf(HlsStorageUnavailableException.class);
        assertThat(input.closed).isTrue();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void readFailureAfterCommitAbortsWithoutAppendingJsonOrDiagnostics() throws Exception {
        for (long size : new long[] {-1, 32768}) {
            var input = new FailingInput(false);
            var response = new MockHttpServletResponse();
            response.setBufferSize(1);
            var controller = controller(input, size);
            assertThatThrownBy(() -> controller.playlist(id.toString(), response))
                    .isInstanceOf(IOException.class).hasMessage("HLS storage stream interrupted").hasNoCause();
            assertThat(input.closed).isTrue();
            assertThat(response.isCommitted()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsByteArray()).hasSize(16384).containsOnly((byte) 0);
        }
    }

    private static final class FailingInput extends InputStream {
        private boolean fail;
        private boolean closed;
        FailingInput(boolean fail) { this.fail = fail; }
        @Override public int read() throws IOException { throw new IOException("private storage diagnostics"); }
        @Override public int read(byte[] b, int offset, int length) throws IOException {
            if (fail) throw new IOException("private storage diagnostics");
            fail = true;
            return length;
        }
        @Override public void close() { closed = true; }
    }
}
