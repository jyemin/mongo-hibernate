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

package com.mongodb.hibernate.internal.translate.mqlv2;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;

/**
 * Mutable translation state threaded through {@link Mqlv2IrEmitters} during IR construction.
 * Holds the JDBC parameter binder list (shared with {@code Mqlv2SelectTranslator}) and the
 * qualifier-rendering rules currently active for column references.
 *
 * <p>Phase B introduction. Phase C/D may extend this with a correlated-binding stack, outer-
 * qualifier set, and let-binding scope.
 *
 * @hidden
 */
public final class Mqlv2TranslationContext {

    private final List<JdbcParameterBinder> parameterBinders;
    private final Map<String, String> unnestAliasToFieldPath;
    private final boolean hasJoins;
    /**
     * Maps aggregate signature (e.g., {@code "count:id"}) to the assigned alias name (e.g., {@code "_agg0"}).
     * Used by {@link com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2IrEmitters#translateExpression} to resolve
     * aggregate function references in HAVING clauses. Empty when not translating a HAVING predicate.
     */
    private final Map<String, String> aggSignatureToName;

    public Mqlv2TranslationContext(
            List<JdbcParameterBinder> parameterBinders,
            Map<String, String> unnestAliasToFieldPath,
            boolean hasJoins) {
        this(parameterBinders, unnestAliasToFieldPath, hasJoins, Collections.emptyMap());
    }

    public Mqlv2TranslationContext(
            List<JdbcParameterBinder> parameterBinders,
            Map<String, String> unnestAliasToFieldPath,
            boolean hasJoins,
            Map<String, String> aggSignatureToName) {
        this.parameterBinders = parameterBinders;
        this.unnestAliasToFieldPath = unnestAliasToFieldPath;
        this.hasJoins = hasJoins;
        this.aggSignatureToName = aggSignatureToName;
    }

    /**
     * Append {@code binder} to the binder list and return the resulting {@code $pN} index.
     */
    public int allocateParameter(JdbcParameterBinder binder) {
        int idx = parameterBinders.size();
        parameterBinders.add(binder);
        return idx;
    }

    public Map<String, String> unnestAliasToFieldPath() {
        return unnestAliasToFieldPath;
    }

    public boolean hasJoins() {
        return hasJoins;
    }

    public Map<String, String> aggSignatureToName() {
        return aggSignatureToName;
    }
}
