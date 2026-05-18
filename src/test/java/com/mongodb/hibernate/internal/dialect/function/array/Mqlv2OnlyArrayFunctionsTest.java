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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import java.util.List;
import org.hibernate.type.spi.TypeConfiguration;
import org.junit.jupiter.api.Test;

/**
 * LSP-guard tests for the three MQLv2-only array function descriptors.
 *
 * <p>Each class overrides {@code render()} to throw {@link FeatureNotSupportedException} — the MQLv2 translator
 * intercepts these functions by name before {@code render()} is reached, so under MQLv2 the throw is unreachable.
 * Under MQLv1 (or any non-MQLv2 path that calls {@code render()}), the exception surfaces immediately with a message
 * identifying the function as MQLv2-only.
 *
 * <p>These tests document and lock that contract at the class level.
 */
class Mqlv2OnlyArrayFunctionsTest {

    @Test
    void arrayLengthRenderThrowsMqlv2Only() {
        var fn = new Mqlv2OnlyArrayLengthFunction(new TypeConfiguration());
        assertThatThrownBy(() -> fn.render(null, List.of(), null, null))
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessageContaining("MQLv2")
                .hasMessageContaining("array_length");
    }

    @Test
    void arrayGetRenderThrowsMqlv2Only() {
        var fn = new Mqlv2OnlyArrayGetFunction();
        assertThatThrownBy(() -> fn.render(null, List.of(), null, null))
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessageContaining("MQLv2")
                .hasMessageContaining("array_get");
    }

    @Test
    void arrayIntersectsRenderThrowsMqlv2Only() {
        var fn = new Mqlv2OnlyArrayIntersectsFunction(false, new TypeConfiguration());
        assertThatThrownBy(() -> fn.render(null, List.of(), null, null))
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessageContaining("MQLv2")
                .hasMessageContaining("array_intersects");
    }

    @Test
    void arrayIntersectsNullableRenderThrowsMqlv2Only() {
        var fn = new Mqlv2OnlyArrayIntersectsFunction(true, new TypeConfiguration());
        assertThatThrownBy(() -> fn.render(null, List.of(), null, null))
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessageContaining("MQLv2")
                .hasMessageContaining("array_intersects");
    }
}
