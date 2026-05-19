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
import org.hibernate.dialect.function.array.AbstractArrayIntersectsFunction;
import org.hibernate.metamodel.model.domain.ReturnableType;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * MQLv2-only descriptor for {@code array_intersects(a, b)} and the nullable variant. Intercepted by the MQLv2
 * translator via the function-name dispatch in
 * {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2ExpressionEmitter#translateExpression} and emitted as {@code (a
 * any (let $__x = $ in b any ($ <eqOp> $__x)))} where {@code eqOp} is {@code ==} for the non-nullable variant and
 * {@code is} for {@code _nullable}.
 *
 * <p>Inherits Hibernate's argument-validator and return-type-resolver wiring from
 * {@link AbstractArrayIntersectsFunction}; the inherited {@code render()} is overridden to throw — under MQLv1 this
 * signals an unsupported function, under MQLv2 the translator intercepts before {@code render()} is reached.
 *
 * @hidden
 */
public final class Mqlv2OnlyArrayIntersectsFunction extends AbstractArrayIntersectsFunction {
    public Mqlv2OnlyArrayIntersectsFunction(boolean nullable, TypeConfiguration typeConfiguration) {
        super(nullable, typeConfiguration);
    }

    @Override
    public void render(
            SqlAppender sqlAppender,
            List<? extends SqlAstNode> sqlAstArguments,
            ReturnableType<?> returnType,
            SqlAstTranslator<?> walker) {
        throw new FeatureNotSupportedException(
                "array_intersects() / array_overlaps() are only supported by the MQLv2 translator");
    }
}
