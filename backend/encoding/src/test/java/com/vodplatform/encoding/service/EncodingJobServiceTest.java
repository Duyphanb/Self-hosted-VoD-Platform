package com.vodplatform.encoding.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.vodplatform.catalog.service.*;
import com.vodplatform.encoding.messaging.*;
import com.vodplatform.encoding.persistence.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class EncodingJobServiceTest {
    final VideoAssetRepository assets=mock(VideoAssetRepository.class);
    final EncodingJobRepository jobs=mock(EncodingJobRepository.class);
    final MovieRowLocks locks=mock(MovieRowLocks.class);
    final EncodingJobPublisher publisher=mock(EncodingJobPublisher.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final UUID movie=UUID.randomUUID(),asset=UUID.randomUUID(),job=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
    EncodingJobService service() {
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        LockedMovie lock=mock(LockedMovie.class);when(lock.movieId()).thenReturn(movie);
        when(locks.lockForMediaWrite(movie)).thenReturn(lock);
        when(assets.save(any())).thenAnswer(call->call.getArgument(0));
        return new EncodingJobService(assets,jobs,locks,publisher,clock,manager);
    }
    @Test void ambiguousCommitReadsBackUnderMovieLockAndOnlyReturnsWhenBothRowsExist() {
        var service=service();doThrow(new IllegalStateException("commit response lost")).doNothing().when(manager).commit(any());
        when(assets.existsById(asset)).thenReturn(true);when(jobs.existsById(job)).thenReturn(true);
        service.commitNewUpload(movie,asset,job,"vod-raw","owned-key");
        verify(locks,times(2)).lockForMediaWrite(movie);
        verifyNoInteractions(publisher);
    }
    @Test void absentRowsAndUnknownReadbacksAreDistinctAndSafe() {
        var service=service();doThrow(new IllegalStateException("commit response lost")).doNothing().when(manager).commit(any());
        assertThatThrownBy(()->service.commitNewUpload(movie,asset,job,"vod-raw","owned-key")).isInstanceOf(UploadDatabaseException.class).hasNoCause();
        doThrow(new IllegalStateException("commit response lost")).doNothing().when(manager).commit(any());
        when(assets.existsById(asset)).thenReturn(true);when(jobs.existsById(job)).thenReturn(false);
        assertThatThrownBy(()->service.commitNewUpload(movie,asset,job,"vod-raw","owned-key")).isInstanceOf(UploadCommitUnknownException.class).hasNoCause();
    }
    @Test void confirmedButMarkFailureRemainsEligibleForDuplicatePublication() {
        var service=service();var message=new EncodingJobMessage(job,asset,1);
        when(publisher.publish(any(),any())).thenReturn(new PublishOutcome.Confirmed());
        when(jobs.markPublished(eq(job),any())).thenThrow(new IllegalStateException("database unavailable")).thenReturn(0);
        assertThat(service.publish(message,Optional.empty())).isFalse();
        assertThat(service.publish(message,Optional.empty())).isTrue();
        verify(publisher,times(2)).publish(eq(message),eq(Optional.empty()));
        verify(jobs,times(2)).markPublished(job,clock.instant());
    }
}
