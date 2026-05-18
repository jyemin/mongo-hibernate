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
import org.hibernate.dialect.function.array.ArrayArgumentValidator;
import org.hibernate.metamodel.model.domain.ReturnableType;
import org.hibernate.query.sqm.function.AbstractSqmSelfRenderingFunctionDescriptor;
import org.hibernate.query.sqm.produce.function.StandardArgumentsValidators;
import org.hibernate.query.sqm.produce.function.StandardFunctionReturnTypeResolvers;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * MQLv2-only descriptor for {@code array_length(arr)}. The MQLv2 translator intercepts this function by name in
 * {@code appendExprText} and emits {@code count(arr)}; this descriptor's {@code render()} method is never called under
 * v2 and throws under v1.
 *
 * @hidden
 */
public final class Mqlv2OnlyArrayLengthFunction extends AbstractSqmSelfRenderingFunctionDescriptor {
    public Mqlv2OnlyArrayLengthFunction(TypeConfiguration typeConfiguration) {
        super(
                "array_length",
                StandardArgumentsValidators.composite(
                        StandardArgumentsValidators.exactly(1), ArrayArgumentValidator.DEFAULT_INSTANCE),
                StandardFunctionReturnTypeResolvers.invariant(
                        typeConfiguration.standardBasicTypeForJavaType(Integer.class)),
                null);
    }

    @Override
    public void render(
            SqlAppender sqlAppender,
            List<? extends SqlAstNode> sqlAstArguments,
            ReturnableType<?> returnType,
            SqlAstTranslator<?> walker) {
        throw new FeatureNotSupportedException("array_length() is only supported by the MQLv2 translator");
    }

    @Override
    public String getArgumentListSignature() {
        return "(ARRAY array)";
    }
}
