/*
 * Copyright 2024-present MongoDB, Inc.
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

package com.mongodb.hibernate.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MongoPreparedStatementMqlv2Test {

    @Test
    void countMqlv2Params_none() {
        assertThat(MongoPreparedStatement.countMqlv2Params("from $customers")).isEqualTo(0);
    }

    @Test
    void countMqlv2Params_one() {
        assertThat(MongoPreparedStatement.countMqlv2Params("from $c | match (age > $p0)")).isEqualTo(1);
    }

    @Test
    void countMqlv2Params_two() {
        assertThat(MongoPreparedStatement.countMqlv2Params(
                "from $c | match ((age > $p0) and (name == $p1))")).isEqualTo(2);
    }

    @Test
    void countMqlv2Params_nonContiguous_returnsMaxPlusOne() {
        // $p0 and $p2 present, no $p1: max index is 2, so count = 3
        assertThat(MongoPreparedStatement.countMqlv2Params(
                "from $c | match ((a == $p0) and (b == $p2))")).isEqualTo(3);
    }

    @Test
    void countMqlv2Params_doesNotMatchCollectionRef() {
        // $customers is a collection reference, not a parameter — should not be counted
        assertThat(MongoPreparedStatement.countMqlv2Params("from $customers | match (age > $p0)")).isEqualTo(1);
    }
}
