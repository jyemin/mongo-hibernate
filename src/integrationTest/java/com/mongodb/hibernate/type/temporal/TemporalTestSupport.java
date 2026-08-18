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

package com.mongodb.hibernate.type.temporal;

import java.time.ZoneId;
import java.util.TimeZone;
import java.util.concurrent.Callable;

final class TemporalTestSupport {

    /**
     * Every test class calling {@link #withSystemTimeZone(ZoneId, Runnable)} must be annotated
     * {@code @ResourceLock(SYSTEM_TIME_ZONE_LOCK)}: the default time zone is JVM-global, while integration test classes
     * run concurrently in a single JVM.
     */
    static final String SYSTEM_TIME_ZONE_LOCK = "com.mongodb.hibernate.type.temporal.systemTimeZone";

    private static final TimeZone ORIGINAL_JVM_TIME_ZONE = TimeZone.getDefault();

    private TemporalTestSupport() {}

    static void withSystemTimeZone(ZoneId timeZone, Runnable runnable) {
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
            runnable.run();
        } finally {
            TimeZone.setDefault(ORIGINAL_JVM_TIME_ZONE);
        }
    }

    static <T> T withSystemTimeZone(ZoneId timeZone, Callable<T> callable) throws Exception {
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
            return callable.call();
        } finally {
            TimeZone.setDefault(ORIGINAL_JVM_TIME_ZONE);
        }
    }
}
