/*
 * Copyright 2025-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hibernate.query.mqlv2;

import java.io.Serial;
import java.io.Serializable;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate {@link StatementInspector} that captures every rendered JDBC SQL (which for the v2
 * dialect is a JSON-encoded MQLv2 command) into a thread-local. Used by integration tests that
 * need to assert on the emitted pipeline text in addition to the executed result rows.
 *
 * <p>Wired via {@code @Setting(name = STATEMENT_INSPECTOR, value =
 * "com.mongodb.hibernate.query.mqlv2.MqlCapture")} on the test class's {@code @ServiceRegistry}.
 */
public final class MqlCapture implements StatementInspector, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final ThreadLocal<String> LAST = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        LAST.set(sql);
        return sql;
    }
}
