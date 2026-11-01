package com.vodplatform.upload;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.vodplatform.encoding.service.*;
import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.storage.ObjectStorage;
import jakarta.servlet.MultipartConfigElement;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

class VideoUploadServiceTest {
    final ObjectStorage storage=mock(ObjectStorage.class);
    final EncodingJobService jobs=mock(EncodingJobService.class);
    final UUID movie=UUID.randomUUID();
    final VideoUploadService service=new VideoUploadService(storage,jobs,new UploadProperties("vod-raw",UploadProperties.TYPES),new MultipartConfigElement("unused",100,100,0));
    byte[] iso={0,0,0,8,'f','t','y','p'};
    MockMultipartFile file(String type, byte[] body) { return new MockMultipartFile("file","secret-do-not-use.txt",type,body) {
        @Override public byte[] getBytes() { throw new AssertionError("Whole file buffering forbidden"); }
    }; }
    @ParameterizedTest @ValueSource(strings={"video/mp4","video/quicktime","video/x-matroska","video/webm"})
    void streamsKnownLengthUsesTrustedExtensionAndCommitsBeforePublishing(String type) throws Exception {
        byte[] bytes=type.contains("mp4")||type.contains("quicktime") ? iso : new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3};
        var result=service.upload(movie,file(type,bytes),Optional.empty());
        assertThat(result.status()).isEqualTo("QUEUED");
        String ext=switch(type){case "video/mp4"->"mp4";case "video/quicktime"->"mov";case "video/webm"->"webm";default->"mkv";};
        var order=inOrder(jobs,storage);
        order.verify(jobs).precheck(movie);
        order.verify(storage).put(eq("vod-raw"),eq(movie+"/"+result.videoAssetId()+"."+ext),any(InputStream.class),eq((long)bytes.length),eq(type));
        order.verify(jobs).commitNewUpload(eq(movie),eq(result.videoAssetId()),any(UUID.class),eq("vod-raw"),anyString());
        order.verify(jobs).publish(any(),eq(Optional.empty()));
        verify(storage,never()).delete(anyString(),anyString());
    }
    @ParameterizedTest @ValueSource(strings={"","application/octet-stream","text/plain","video/unknown","broken mime"})
    void rejectsDeclarationsBeforeDatabaseOrStorage(String mime) {
        assertThatThrownBy(()->service.upload(movie,file(mime,iso),Optional.empty())).isInstanceOf(ApiException.class);
        verifyNoInteractions(storage,jobs);
    }
    @Test void rejectsEmptyOversizeAndFamilyMismatch() {
        for(var f:List.of(file("video/mp4",new byte[0]),file("video/mp4",new byte[101]),file("video/webm",iso),file("video/mp4",new byte[]{1,2})))
            assertThatThrownBy(()->service.upload(movie,f,Optional.empty())).isInstanceOf(ApiException.class);
        verifyNoInteractions(jobs,storage);
    }
    @Test void putFailureCompensatesAndNeverCommits() throws Exception {
        doThrow(new IOException("untrusted-secret")).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
        assertThatThrownBy(()->service.upload(movie,file("video/mp4",iso),Optional.empty())).isInstanceOf(UploadStorageException.class).hasMessage("Upload storage operation failed").hasNoCause();
        verify(storage).delete(eq("vod-raw"),matches(movie+"/[0-9a-f-]+\\.mp4"));
        verify(jobs,never()).commitNewUpload(any(),any(),any(),anyString(),anyString());
    }
    @Test void rollbackCompensatesButUnknownCommitRetainsRaw() throws Exception {
        doThrow(new UploadDatabaseException()).when(jobs).commitNewUpload(any(),any(),any(),anyString(),anyString());
        assertThatThrownBy(()->service.upload(movie,file("video/mp4",iso),Optional.empty())).isInstanceOf(UploadDatabaseException.class);
        verify(storage).delete(anyString(),anyString());
        reset(storage);
        doThrow(new UploadCommitUnknownException()).when(jobs).commitNewUpload(any(),any(),any(),anyString(),anyString());
        assertThatThrownBy(()->service.upload(movie,file("video/mp4",iso),Optional.empty())).isInstanceOf(UploadCommitUnknownException.class);
        verify(storage,never()).delete(anyString(),anyString());
    }
    @Test void failedDeleteDoesNotMaskOriginalConflict() throws Exception {
        doThrow(new VideoUploadInProgressException()).when(jobs).commitNewUpload(any(),any(),any(),anyString(),anyString());
        doThrow(new IOException("secret")).when(storage).delete(anyString(),anyString());
        assertThatThrownBy(()->service.upload(movie,file("video/mp4",iso),Optional.empty())).isInstanceOf(VideoUploadInProgressException.class);
    }
    @Test void configuredAllowlistCannotExpandFrozenFamilies() {
        assertThatThrownBy(()->new UploadProperties("vod-raw",Set.of("text/plain"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new UploadProperties("vod-raw",Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"#EXTM3U", "ffconcat version 1.0", "ordinary text", "legacy QuickTime"})
    void renamedTextAndPlaylistsAreRejectedEvenWithAllowedMime(String body) {
        assertThatThrownBy(()->service.upload(movie,file("video/mp4",body.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),Optional.empty()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNSUPPORTED_VIDEO_TYPE");
        verifyNoInteractions(storage,jobs);
    }
    @Test void bothStreamsCloseAndDeclaredMimeParametersAreCanonicalized() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicInteger();
        var file=new MockMultipartFile("file","ignored.webm","VIDEO/MP4; charset=UTF-8",iso) {
            @Override public InputStream getInputStream() {return new FilterInputStream(new ByteArrayInputStream(iso)) {
                @Override public void close() throws IOException {closed.incrementAndGet();super.close();}
            };}
        };
        service.upload(movie,file,Optional.empty());
        assertThat(closed.get()).isEqualTo(2);
        verify(storage).put(eq("vod-raw"),endsWith(".mp4"),any(),eq(8L),eq("video/mp4"));
    }
}
