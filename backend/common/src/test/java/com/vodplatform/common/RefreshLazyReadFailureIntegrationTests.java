package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.common.support.CookieAuthFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.jdbc.datasource.DelegatingDataSource;

@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "CORS_ALLOWED_ORIGINS=http://localhost"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(RefreshLazyReadFailureIntegrationTests.ReadFailureConfiguration.class)
class RefreshLazyReadFailureIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {

    @Autowired
    private LazyReadFailure lazyReadFailure;

    @ParameterizedTest(name = "{0} lazy load with {1} keeps the refresh cookie and rolls back rotation")
    @CsvSource({"users,connection", "users,timeout", "user_roles,connection", "user_roles,timeout"})
    void lazyStateReadsFailAs503WithoutConsumingTheToken(String table, String failureKind) throws Exception {
        var originalCookie = issued();
        lazyReadFailure.arm(table, failureKind);

        refresh(originalCookie)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(lazyReadFailure.tokenLookupSucceeded).isTrue();
        assertThat(lazyReadFailure.failureInjected).isTrue();
        assertThat(revoked()).isZero();
        refresh(originalCookie).andExpect(status().isOk());
        assertThat(revoked()).isEqualTo(1);
    }

    /** Inject at JDBC execution so Hibernate's lazy loader produces the real persistence exception. */
    static final class LazyReadFailure {
        private String table;
        private String failureKind;
        private boolean tokenLookupSucceeded;
        private boolean failureInjected;

        void arm(String targetTable, String targetFailureKind) {
            table = targetTable;
            failureKind = targetFailureKind;
            tokenLookupSucceeded = false;
            failureInjected = false;
        }

        Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        Object result = invoke(connection, method, arguments);
                        if (method.getName().equals("prepareStatement")
                                && result instanceof PreparedStatement statement
                                && arguments[0] instanceof String sql) {
                            return wrap(statement, sql);
                        }
                        return result;
                    });
        }

        private PreparedStatement wrap(PreparedStatement statement, String sql) {
            String normalizedSql = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (!method.getName().equals("executeQuery")) {
                            return invoke(statement, method, arguments);
                        }
                        if (table != null && tokenLookupSucceeded && normalizedSql.contains("from " + table + " ")) {
                            table = null;
                            failureInjected = true;
                            throw availabilityFailure();
                        }
                        Object result = invoke(statement, method, arguments);
                        if (table != null && normalizedSql.contains("from refresh_tokens ")
                                && normalizedSql.contains("token_hash")) {
                            tokenLookupSucceeded = true;
                        }
                        return result;
                    });
        }

        private SQLException availabilityFailure() {
            if (failureKind.equals("timeout")) {
                return new SQLTimeoutException("Synthetic lazy state-read timeout", "HYT00");
            }
            return new SQLTransientConnectionException("Synthetic lazy state-read failure", "08006");
        }

        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadFailureConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static LazyReadFailure lazyReadFailure() {
            return new LazyReadFailure();
        }

        @Bean
        static BeanPostProcessor lazyReadDataSourcePostProcessor(LazyReadFailure lazyReadFailure) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource)) return bean;
                    return new DelegatingDataSource(dataSource) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            return lazyReadFailure.wrap(super.getConnection());
                        }

                        @Override
                        public Connection getConnection(String username, String password) throws SQLException {
                            return lazyReadFailure.wrap(super.getConnection(username, password));
                        }
                    };
                }
            };
        }
    }
}
