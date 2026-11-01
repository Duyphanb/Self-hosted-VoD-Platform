package com.vodplatform.common;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.vodplatform.upload.*;
import com.vodplatform.encoding.service.*;
import com.vodplatform.encoding.messaging.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.test.context.*;

@SpringBootTest(properties={"auth.tokens.secret=test-only-secret-with-at-least-32-bytes","spring.jpa.hibernate.ddl-auto=validate"})
@EnabledIf("configured")
@Import(UploadPostgresTests.Faults.class)
class UploadPostgresTests extends UploadTransactionTests {
    static final AtomicReference<String> fault=new AtomicReference<>("");
    static final AtomicBoolean failReadback=new AtomicBoolean();
    static boolean configured() {return "true".equals(System.getenv("ENCODING_TEST_POSTGRES_REQUIRED")) || System.getenv("ENCODING_TEST_POSTGRES_URL")!=null;}
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("ENCODING_TEST_POSTGRES_URL");
        if (url==null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/vod.*")) throw new IllegalStateException("Disposable loopback PostgreSQL is required");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->System.getenv("ENCODING_TEST_POSTGRES_USER"));
        registry.add("spring.datasource.password",()->System.getenv("ENCODING_TEST_POSTGRES_PASSWORD"));
    }
    @AfterEach void resetFaults() {fault.set("");failReadback.set(false);}
    @Test void commitThenThrowIsReadBackWithoutDeletingOrDuplicating() throws Exception {
        UUID movie=movie("DRAFT");
        doAnswer(call->{fault.set("committed");return null;}).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
        var response=upload.upload(movie,file(),Optional.empty());
        assertThat(jdbc.queryForObject("select count(*) from encoding_jobs where video_asset_id=?",Integer.class,response.videoAssetId())).isEqualTo(1);
        verify(storage,never()).delete(anyString(),anyString());verify(publisher).publish(any(),any());
    }
    @Test void rollbackThenThrowCompensatesOnlyAfterReadback() throws Exception {
        UUID movie=movie("DRAFT");
        doAnswer(call->{fault.set("rolledback");return null;}).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
        assertThatThrownBy(()->upload.upload(movie,file(),Optional.empty())).isInstanceOf(UploadDatabaseException.class);
        verify(storage).delete(anyString(),anyString());verify(publisher,never()).publish(any(),any());
    }
    @Test void failedReadbackRetainsRawAndReportsUnknown() throws Exception {
        UUID movie=movie("DRAFT");
        doAnswer(call->{fault.set("unknown");return null;}).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
        assertThatThrownBy(()->upload.upload(movie,file(),Optional.empty())).isInstanceOf(UploadCommitUnknownException.class);
        failReadback.set(false);
        verify(storage,never()).delete(anyString(),anyString());verify(publisher,never()).publish(any(),any());
    }
    @Test void parallelUploadsHaveOneWinnerAndCompensateLoser() throws Exception {
        UUID movie=movie("DRAFT");CountDownLatch puts=new CountDownLatch(2);
        doAnswer(call->{puts.countDown();assertThat(puts.await(10,TimeUnit.SECONDS)).isTrue();return null;}).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Object> run=()->{try{return upload.upload(movie,file(),Optional.empty());}catch(VideoUploadInProgressException conflict){return conflict;}};
            var x=pool.submit(run);var y=pool.submit(run);
            var results=List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));
            assertThat(results.stream().filter(VideoAssetStatusResponse.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(VideoUploadInProgressException.class::isInstance).count()).isEqualTo(1);
            verify(storage,times(1)).delete(anyString(),anyString());
            assertThat(jdbc.queryForObject("select count(*) from video_assets where movie_id=?",Integer.class,movie)).isEqualTo(1);
        }
    }
    @Test void putStartDoesNotDetermineCreatedOrderAndReadyFallbackIsPreserved() throws Exception {
        UUID movie=movie("DRAFT");CountDownLatch firstPut=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==1){firstPut.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();}return null;}).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->upload.upload(movie,file(),Optional.empty()));
            assertThat(firstPut.await(10,TimeUnit.SECONDS)).isTrue();
            var second=upload.upload(movie,file(),Optional.empty());
            jdbc.update("update video_assets set status='READY' where id=?",second.videoAssetId());
            jdbc.update("update encoding_jobs set status='READY' where video_asset_id=?",second.videoAssetId());
            release.countDown();var newer=first.get(30,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select id from video_assets where movie_id=? order by created_at desc,id desc limit 1",UUID.class,movie)).isEqualTo(newer.videoAssetId());
            assertThat(jdbc.queryForObject("select id from video_assets where movie_id=? and status='READY' order by created_at desc,id desc limit 1",UUID.class,movie)).isEqualTo(second.videoAssetId());
            jdbc.update("update video_assets set status='READY' where id=?",newer.videoAssetId());
            assertThat(jdbc.queryForObject("select id from video_assets where movie_id=? and status='READY' order by created_at desc,id desc limit 1",UUID.class,movie)).isEqualTo(newer.videoAssetId());
        } finally {release.countDown();}
    }
    @org.springframework.beans.factory.annotation.Autowired DataSource source;
    @Test void authoritativeIndexLoserIsTranslatedAfterBlockedInsertAndCompensated() throws Exception {
        UUID movie=movie("DRAFT"),heldAsset=UUID.randomUUID();
        CountDownLatch inserted=new CountDownLatch(1);
        try(var connection=source.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            doAnswer(call->{
                try(var statement=connection.prepareStatement("insert into video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) values (?,?,'vod-raw','fixture-held','QUEUED',now(),now())")) {
                    statement.setObject(1,heldAsset);statement.setObject(2,movie);statement.executeUpdate();
                }
                inserted.countDown();return null;
            }).when(storage).put(anyString(),anyString(),any(),anyLong(),anyString());
            var loser=pool.submit(()->upload.upload(movie,file(),Optional.empty()));
            assertThat(inserted.await(10,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like '%video_assets%')",Boolean.class));
                if(waiting)break;Thread.sleep(20);
            }
            assertThat(waiting).as("service insertion waits on the uncommitted unique index entry").isTrue();
            connection.commit();
            assertThatThrownBy(()->loser.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(VideoUploadInProgressException.class);
            verify(storage).delete(anyString(),anyString());verify(publisher,never()).publish(any(),any());
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Faults {
        @Bean static BeanPostProcessor commitFaults() {return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if (!(bean instanceof DataSource)) return bean;
                return Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
                    if (method.getName().equals("getConnection") && failReadback.getAndSet(false)) throw new SQLException("fixture readback unavailable");
                    Object result=invoke(bean,method,args);
                    if (!(result instanceof Connection connection)) return result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                        if (m.getName().equals("commit")) {
                            String mode=fault.getAndSet("");
                            if (!mode.isEmpty()) {
                                if(mode.equals("rolledback")) connection.rollback();else connection.commit();
                                if(mode.equals("unknown")) failReadback.set(true);
                                throw new SQLException("fixture ambiguous commit");
                            }
                        }
                        return invoke(connection,m,a);
                    });
                });
            }
        };}
        static Object invoke(Object target,Method method,Object[] args) throws Throwable {
            try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        }
    }
}
