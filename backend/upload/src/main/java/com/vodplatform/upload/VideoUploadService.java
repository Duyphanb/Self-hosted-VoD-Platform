package com.vodplatform.upload;

import com.vodplatform.encoding.service.*;
import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.shared.storage.ObjectStorage;
import com.vodplatform.shared.error.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.MultipartConfigElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class VideoUploadService {
    private static final Logger log=LoggerFactory.getLogger(VideoUploadService.class);
    private final ObjectStorage storage;
    private final EncodingJobService jobs;
    private final UploadProperties properties;
    private final MultipartConfigElement multipart;
    public VideoUploadService(ObjectStorage storage, EncodingJobService jobs, UploadProperties properties, MultipartConfigElement multipart) {
        this.storage=storage; this.jobs=jobs; this.properties=properties;this.multipart=multipart;
    }
    public VideoAssetStatusResponse upload(UUID movieId, MultipartFile file, Optional<String> requestId) {
        EncodingJobService.requireNoTransaction();
        Objects.requireNonNull(movieId,"movieId");Objects.requireNonNull(requestId,"requestId");
        if (file==null || file.isEmpty() || file.getSize()<=0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","File must not be empty",List.of(new ApiFieldError("file","must not be empty")));
        if (file.getSize()>multipart.getMaxFileSize()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"PAYLOAD_TOO_LARGE","File exceeds upload size limit");
        String mime;
        try {
            var declared=MimeType.valueOf(Objects.requireNonNullElse(file.getContentType(),""));
            mime=(declared.getType()+"/"+declared.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) { throw unsupported(); }
        if (!properties.allowedMimeTypes().contains(mime)) throw unsupported();
        boolean iso=mime.equals("video/mp4") || mime.equals("video/quicktime");
        try (var input=file.getInputStream()) {
            byte[] prefix=input.readNBytes(8);
            boolean matches=iso ? prefix.length>=8 && prefix[4]=='f' && prefix[5]=='t' && prefix[6]=='y' && prefix[7]=='p'
                    : prefix.length>=4 && prefix[0]==0x1a && prefix[1]==0x45 && prefix[2]==(byte)0xdf && prefix[3]==(byte)0xa3;
            if (!matches) throw unsupported();
        } catch (IOException failure) { throw new UploadStorageException(); }
        jobs.precheck(movieId);
        UUID assetId=UUID.randomUUID(),jobId=UUID.randomUUID();
        String extension=switch(mime) { case "video/mp4" -> "mp4";case "video/quicktime" -> "mov";case "video/webm" -> "webm";default -> "mkv"; };
        String key=movieId+"/"+assetId+"."+extension;
        try (var input=file.getInputStream()) { storage.put(properties.rawBucket(),key,input,file.getSize(),mime); }
        catch (IOException | RuntimeException failure) {
            compensate(movieId,assetId,jobId,key);
            throw new UploadStorageException();
        }
        try { jobs.commitNewUpload(movieId,assetId,jobId,properties.rawBucket(),key); }
        catch (UploadCommitUnknownException unknown) { throw unknown; }
        catch (RuntimeException failure) { compensate(movieId,assetId,jobId,key);throw failure; }
        jobs.publish(new EncodingJobMessage(jobId,assetId,1),requestId);
        log.info("upload accepted movieId={} videoAssetId={} encodingJobId={}",movieId,assetId,jobId);
        return VideoAssetStatusResponse.queued(assetId);
    }
    private void compensate(UUID movieId,UUID assetId,UUID jobId,String key) {
        try {
            storage.delete(properties.rawBucket(),key);
            log.info("upload compensated movieId={} videoAssetId={} encodingJobId={}",movieId,assetId,jobId);
        }
        catch (IOException | RuntimeException failure) {
            log.warn("upload compensation failed movieId={} videoAssetId={} encodingJobId={}",movieId,assetId,jobId);
        }
    }
    private static ApiException unsupported() {
        return new ApiException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_VIDEO_TYPE","Unsupported video type or signature");
    }
}
