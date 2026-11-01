package com.vodplatform.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.vodplatform.upload.*;
import com.vodplatform.encoding.service.*;
import com.vodplatform.encoding.messaging.*;
import com.vodplatform.shared.storage.ObjectStorage;
import java.io.InputStream;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties={"auth.tokens.secret=test-only-secret-with-at-least-32-bytes"})
class UploadTransactionTests {
    @Autowired VideoUploadService upload;
    @Autowired EncodingJobService service;
    @Autowired EncodingRelay relay;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ObjectStorage storage;
    @MockitoBean EncodingJobPublisher publisher;
    final List<UUID> movies=new ArrayList<>();
    @BeforeEach void configure() throws Exception {
        when(publisher.confirmTimeout()).thenReturn(Duration.ofSeconds(5));
        when(publisher.publish(any(),any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new PublishOutcome.Confirmed();});
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((InputStream)call.getArgument(2)).readAllBytes()).hasSize(8);return null;
        }).when(storage).put(anyString(),anyString(),any(),eq(8L),eq("video/mp4"));
    }
    UUID movie(String status) {
        UUID id=UUID.randomUUID();jdbc.update("insert into movies (id,title,slug,description,status,created_at,updated_at) values (?,?,?,? ,?,?,?)",id,"Upload fixture","upload-"+id,"fixture",status,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));movies.add(id);return id;
    }
    MockMultipartFile file() {return new MockMultipartFile("file","untrusted.mov","video/mp4",new byte[]{0,0,0,8,'f','t','y','p'});}
    @AfterEach void clean() { for(UUID id:movies) jdbc.update("delete from movies where id=?",id); }
    @Test void pairCommitsBeforeSendForEveryMovieStatusAndConfirmedOnlyMarksPublication() throws Exception {
        for(String status:List.of("DRAFT","PUBLISHED","ARCHIVED")) {
            UUID id=movie(status);
            doAnswer(call->{
                EncodingJobMessage message=call.getArgument(0);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(jdbc.queryForObject("select status from encoding_jobs where id=?",String.class,message.encodingJobId())).isEqualTo("QUEUED");
                return new PublishOutcome.Confirmed();
            }).when(publisher).publish(any(),any());
            var result=upload.upload(id,file(),Optional.empty());assertThat(result.status()).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("select published_at from encoding_jobs where video_asset_id=?",Timestamp.class,result.videoAssetId())).isNotNull();
        }
        verify(storage,never()).delete(anyString(),anyString());
    }
    @Test void allNonConfirmedOutcomesKeepRawAndPendingJob() throws Exception {
        for(PublishOutcome outcome:List.of(new PublishOutcome.Returned(312,"NO_ROUTE"),new PublishOutcome.Nacked(null),new PublishOutcome.TimedOut(),new PublishOutcome.Failed(PublishOutcome.Kind.UNAVAILABLE))) {
            when(publisher.publish(any(),any())).thenReturn(outcome);
            var result=upload.upload(movie("DRAFT"),file(),Optional.empty());
            assertThat(result.status()).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("select published_at from encoding_jobs where video_asset_id=?",Timestamp.class,result.videoAssetId())).isNull();
        }
        verify(storage,never()).delete(anyString(),anyString());
    }
    @Test void conflictAndDatabaseRollbackNeverPublishAndCompensate() throws Exception {
        UUID id=movie("DRAFT");
        doAnswer(call->{jdbc.update("delete from movies where id=?",id);return null;}).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
        assertThatThrownBy(()->upload.upload(id,file(),Optional.empty())).isInstanceOf(com.vodplatform.catalog.exception.MovieNotFoundException.class);
        verify(publisher,never()).publish(any(),any());verify(storage).delete(anyString(),anyString());
    }
    @Test void relayHonorsGraceRecoversCrashAndNeverOverwritesFastWorker() {
        when(publisher.publish(any(),any())).thenReturn(new PublishOutcome.TimedOut());
        var result=upload.upload(movie("DRAFT"),file(),Optional.empty());
        reset(publisher);when(publisher.confirmTimeout()).thenReturn(Duration.ofSeconds(5));
        relay.relayOnce();verify(publisher,never()).publish(any(),any());
        jdbc.update("update encoding_jobs set queued_at=? where video_asset_id=?",Timestamp.from(Instant.now().minusSeconds(60)),result.videoAssetId());
        when(publisher.publish(any(),any())).thenAnswer(call->{
            EncodingJobMessage message=call.getArgument(0);
            jdbc.update("update encoding_jobs set status='READY' where id=?",message.encodingJobId());
            jdbc.update("update video_assets set status='READY' where id=?",message.videoAssetId());
            return new PublishOutcome.Confirmed();
        });
        relay.relayOnce();
        assertThat(jdbc.queryForObject("select status from encoding_jobs where video_asset_id=?",String.class,result.videoAssetId())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select published_at from encoding_jobs where video_asset_id=?",Timestamp.class,result.videoAssetId())).isNotNull();
        relay.relayOnce();verify(publisher,times(1)).publish(any(),any());
    }
    @Test void relayBatchIsBoundedOrderedAndStopsAfterFirstFailure() {
        UUID movie=movie("DRAFT");
        // Terminal assets allow a backlog fixture without violating the in-flight index.
        for(int n=0;n<55;n++) {
            UUID asset=UUID.randomUUID(),job=UUID.randomUUID();
            var at=Timestamp.from(Instant.now().minusSeconds(200-n));
            jdbc.update("insert into video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) values (?,?,'vod-raw',?,'FAILED',?,?)",asset,movie,asset.toString(),at,at);
            jdbc.update("insert into encoding_jobs (id,video_asset_id,status,attempt,queued_at) values (?,?,'QUEUED',1,?)",job,asset,at);
        }
        when(publisher.publish(any(),any())).thenReturn(new PublishOutcome.TimedOut());
        relay.relayOnce();verify(publisher,times(1)).publish(any(),any());
        reset(publisher);when(publisher.confirmTimeout()).thenReturn(Duration.ofSeconds(5));
        List<UUID> sent=new ArrayList<>();
        when(publisher.publish(any(),any())).thenAnswer(call->{sent.add(((EncodingJobMessage)call.getArgument(0)).encodingJobId());return new PublishOutcome.Confirmed();});
        relay.relayOnce();
        assertThat(sent).hasSize(50);
        assertThat(sent).containsExactlyElementsOf(jdbc.queryForList("select id from encoding_jobs where video_asset_id in (select id from video_assets where movie_id=?) order by queued_at,id limit 50",UUID.class,movie));
        relay.relayOnce();assertThat(sent).hasSize(55);
    }
}
