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

package com.mongodb.hibernate.internal.dialect.function.array;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import java.util.List;
import org.hibernate.dialect.function.array.ArrayGetUnnestFunction;
import org.hibernate.metamodel.model.domain.ReturnableType;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;

/**
 * MQLv2-only descriptor for {@code array_get(arr, i)}. Intercepted by the v2 translator
 * and emitted as {@code arr[(i) - 1]} (HQL is 1-based, MQLv2 is 0-based). If
 * {@code render()} is ever invoked (it is not under v2 because the translator intercepts
 * the function by name), it throws.
 *
 * <p>Extends Hibernate's {@link ArrayGetUnnestFunction} solely to inherit its
 * argument-validator and return-type-resolver wiring; the parent's SQL-text-emitting
 * {@code render()} is not reachable under v2.
 *
 * @hidden
 */
public final class Mqlv2OnlyArrayGetFunction extends ArrayGetUnnestFunction {
    @Override
    public void render(
            SqlAppender sqlAppender,
            List<? extends SqlAstNode> sqlAstArguments,
            ReturnableType<?> returnType,
            SqlAstTranslator<?> walker) {
        throw new FeatureNotSupportedException("array_get() is only supported by the MQLv2 translator");
    }
}
