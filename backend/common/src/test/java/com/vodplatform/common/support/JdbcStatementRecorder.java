package com.vodplatform.common.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;

/**
 * Wraps a DataSource and records, for every connection it hands out, the SQL text of each prepared statement, its
 * bound parameters and the values read from its result set. With a DataSourceTransactionManager on the wrapper, one
 * connection is one transaction, so a test can check what each transaction ran without depending on the query plan.
 *
 * <p>Supported calls: {@code prepareStatement}, the {@code set...} binders, {@code executeQuery} (its result set is
 * recorded through {@code getObject}) and {@code executeUpdate}. Each execution is recorded separately. Every other
 * {@code execute...} method, batches and plain {@code Statement}s are rejected, so that nothing runs unrecorded.
 */
public final class JdbcStatementRecorder {

    public record RecordedStatement(String sql, List<Object> parameters, List<Object> resultValues) {

        public boolean isDelete() {
            return sql.stripLeading().toUpperCase().startsWith("DELETE");
        }
    }

    private final List<List<RecordedStatement>> connections = new CopyOnWriteArrayList<>();

    public DataSource wrap(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (method.getName().equals("getConnection")) {
                        List<RecordedStatement> statements = new CopyOnWriteArrayList<>();
                        connections.add(statements);
                        return recordingConnection((Connection) result, statements);
                    }
                    return result;
                });
    }

    /** The statements of each connection (transaction), in the order the connections were opened. */
    public List<List<RecordedStatement>> transactions() {
        return Collections.unmodifiableList(connections);
    }

    private Connection recordingConnection(Connection target, List<RecordedStatement> statements) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("createStatement") || name.equals("prepareCall")) {
                        throw new UnsupportedOperationException(name + " is not recorded; use prepareStatement");
                    }
                    Object result = invoke(target, method, args);
                    if (name.equals("prepareStatement")) {
                        String sql = (String) args[0];
                        return recordingStatement((PreparedStatement) result, sql, statements);
                    }
                    return result;
                });
    }

    private PreparedStatement recordingStatement(PreparedStatement target, String sql,
                                                 List<RecordedStatement> statements) {
        Map<Integer, Object> parameters = new TreeMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    boolean recordedExecution = name.equals("executeQuery") || name.equals("executeUpdate");
                    boolean otherExecution = name.startsWith("execute") || name.equals("addBatch");
                    if (otherExecution && !recordedExecution) {
                        throw new UnsupportedOperationException(name + " is not recorded");
                    }
                    boolean bindsParameter = name.startsWith("set")
                            && args != null
                            && args.length >= 2
                            && args[0] instanceof Integer;
                    if (bindsParameter) {
                        parameters.put((Integer) args[0], args[1]);
                    }
                    if (!name.equals("executeQuery") && !name.equals("executeUpdate")) {
                        return invoke(target, method, args);
                    }

                    List<Object> boundValues = Collections.unmodifiableList(new ArrayList<>(parameters.values()));
                    List<Object> resultValues = new CopyOnWriteArrayList<>();
                    statements.add(new RecordedStatement(sql, boundValues, resultValues));
                    Object result = invoke(target, method, args);
                    if (name.equals("executeQuery")) {
                        return recordingResultSet((ResultSet) result, resultValues);
                    }
                    return result;
                });
    }

    private ResultSet recordingResultSet(ResultSet target, List<Object> resultValues) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (method.getName().equals("getObject")) {
                        resultValues.add(result);
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
