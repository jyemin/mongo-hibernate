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
import com.mongodb.hibernate.internal.translate.Mqlv2TranslatorFactory;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;

/** Test dialect with MQLv2 translation always enabled. */
public final class TestMqlv2Dialect extends TestMongoDialect {
    public TestMqlv2Dialect(DialectResolutionInfo info) {
        super(info);
    }

    public TestMqlv2Dialect() {
        super();
    }

    @Override
    public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
        return new Mqlv2TranslatorFactory();
    }
}
