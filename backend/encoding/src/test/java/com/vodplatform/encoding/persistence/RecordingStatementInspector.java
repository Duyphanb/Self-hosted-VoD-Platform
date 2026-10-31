package com.vodplatform.encoding.persistence;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/** Counts actual ORM statements on the test thread, independently of repository invocations. */
public final class RecordingStatementInspector implements StatementInspector {
    private static final ThreadLocal<List<String>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);

    public static void clear() { STATEMENTS.get().clear(); }
    public static List<String> statements() { return List.copyOf(STATEMENTS.get()); }

    @Override
    public String inspect(String sql) {
        STATEMENTS.get().add(sql);
        return sql;
    }
}
