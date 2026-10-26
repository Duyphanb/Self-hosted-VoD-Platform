package com.vodplatform.catalog.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Records every SQL statement Hibernate prepares, so tests can check the statements a service
 * issues. Hibernate creates it from the class name, hence the public no-argument constructor.
 */
public final class RecordingStatementInspector implements StatementInspector {

    private static final long serialVersionUID = 1L;

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    static void clear() {
        STATEMENTS.clear();
    }

    static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }
}
