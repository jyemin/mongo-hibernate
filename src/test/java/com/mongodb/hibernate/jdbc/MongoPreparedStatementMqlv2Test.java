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

import org.bson.BsonBoolean;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

class MongoPreparedStatementMqlv2Test {

    @Test
    void countMqlv2Params_none() {
        assertThat(MongoPreparedStatement.countMqlv2Params("from $customers")).isEqualTo(0);
    }

    @Test
    void countMqlv2Params_one() {
        assertThat(MongoPreparedStatement.countMqlv2Params("from $c | match (age > {?0})")).isEqualTo(1);
    }

    @Test
    void countMqlv2Params_two() {
        assertThat(MongoPreparedStatement.countMqlv2Params(
                "from $c | match ((age > {?0}) and (name == {?1}))")).isEqualTo(2);
    }

    @Test
    void renderBsonAsMqlv2Literal_string() throws Exception {
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(new BsonString("shipped")))
                .isEqualTo("\"shipped\"");
    }

    @Test
    void renderBsonAsMqlv2Literal_stringWithSpecialChars() throws Exception {
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(new BsonString("foo\"bar")))
                .isEqualTo("\"foo\\\"bar\"");
    }

    @Test
    void renderBsonAsMqlv2Literal_int() throws Exception {
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(new BsonInt32(42))).isEqualTo("42");
    }

    @Test
    void renderBsonAsMqlv2Literal_long() throws Exception {
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(new BsonInt64(9_000_000_000L)))
                .isEqualTo("9000000000");
    }

    @Test
    void renderBsonAsMqlv2Literal_double() throws Exception {
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(new BsonDouble(3.14))).isEqualTo("3.14");
    }

    @Test
    void renderBsonAsMqlv2Literal_boolean() throws Exception {
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(BsonBoolean.TRUE)).isEqualTo("true");
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(BsonBoolean.FALSE)).isEqualTo("false");
    }

    @Test
    void renderBsonAsMqlv2Literal_null() throws Exception {
        assertThat(MongoPreparedStatement.renderBsonAsMqlv2Literal(BsonNull.VALUE)).isEqualTo("null");
    }

    @Test
    void substituteMqlv2Params_replacesPlaceholders() throws Exception {
        var result = MongoPreparedStatement.substituteMqlv2Params(
                "from $c | match ((status == {?0}) and (age > {?1}))",
                new org.bson.BsonValue[]{new BsonString("shipped"), new BsonInt32(25)});
        assertThat(result).isEqualTo("from $c | match ((status == \"shipped\") and (age > 25))");
    }

    @Test
    void countMqlv2Params_nonContiguous_returnsMaxPlusOne() {
        // {?0} and {?2} present, no {?1}: max index is 2, so count = 3
        assertThat(MongoPreparedStatement.countMqlv2Params(
                "from $c | match ((a == {?0}) and (b == {?2}))")).isEqualTo(3);
    }
}
