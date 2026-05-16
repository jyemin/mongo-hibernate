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

import com.mongodb.hibernate.internal.translate.Mqlv2TranslatorFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.MutationStatement;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.exec.spi.JdbcOperationQueryMutation;
import org.hibernate.sql.exec.spi.JdbcSelect;
import org.hibernate.sql.model.ast.TableMutation;
import org.hibernate.sql.model.jdbc.JdbcMutationOperation;

/**
 * Test-only wrapper that captures every {@link SelectStatement} passed through
 * {@link Mqlv2TranslatorFactory#buildSelectTranslator}, exposing the most recently captured statement via a
 * thread-local. Mutation translation is delegated unchanged.
 *
 * <p>Used by diagnostic tests that need to inspect the SQL AST shape Hibernate produces for a given HQL query, without
 * modifying production code.
 */
public final class CapturingMqlv2TranslatorFactory implements SqlAstTranslatorFactory {

    private static final ThreadLocal<SelectStatement> LAST_CAPTURED = new ThreadLocal<>();

    private final Mqlv2TranslatorFactory delegate = new Mqlv2TranslatorFactory();

    /** Returns and clears the most recently captured {@link SelectStatement} on this thread. */
    public static SelectStatement takeLastCaptured() {
        var stmt = LAST_CAPTURED.get();
        LAST_CAPTURED.remove();
        return stmt;
    }

    /** Clears the thread-local without consuming the captured statement. */
    public static void reset() {
        LAST_CAPTURED.remove();
    }

    @Override
    public SqlAstTranslator<JdbcSelect> buildSelectTranslator(
            SessionFactoryImplementor sessionFactory, SelectStatement selectStatement) {
        LAST_CAPTURED.set(selectStatement);
        return delegate.buildSelectTranslator(sessionFactory, selectStatement);
    }

    @Override
    public SqlAstTranslator<? extends JdbcOperationQueryMutation> buildMutationTranslator(
            SessionFactoryImplementor sessionFactory, MutationStatement mutationStatement) {
        return delegate.buildMutationTranslator(sessionFactory, mutationStatement);
    }

    @Override
    public <O extends JdbcMutationOperation> SqlAstTranslator<O> buildModelMutationTranslator(
            TableMutation<O> tableMutation, SessionFactoryImplementor sessionFactory) {
        return delegate.buildModelMutationTranslator(tableMutation, sessionFactory);
    }
}
