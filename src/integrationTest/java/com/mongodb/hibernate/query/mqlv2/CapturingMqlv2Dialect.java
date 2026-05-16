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

import com.mongodb.hibernate.internal.dialect.TestMongoDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;

/**
 * Test-only dialect that installs {@link CapturingMqlv2TranslatorFactory}. Extends {@link TestMongoDialect} directly
 * (rather than {@code TestMqlv2Dialect}, which is final) and re-implements the v2 translator-factory override.
 */
public final class CapturingMqlv2Dialect extends TestMongoDialect {
    public CapturingMqlv2Dialect(DialectResolutionInfo info) {
        super(info);
    }

    public CapturingMqlv2Dialect() {
        super();
    }

    @Override
    public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
        return new CapturingMqlv2TranslatorFactory();
    }
}
