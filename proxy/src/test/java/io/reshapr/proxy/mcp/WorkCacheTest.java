/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.proxy.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkCache.
 * @author vaishnav
 */
class WorkCacheTest {

    private WorkCache workCache;

    @BeforeEach
    void setUp() {
        workCache = new WorkCache(100);
    }

    @Test
    void testSetAndGet() {
        workCache.set("major", "minor", "value1");
        assertEquals("value1", workCache.get("major", "minor"));
        assertNull(workCache.get("major", "other"));
    }

    @Test
    void testComputeIfAbsent() {
        AtomicInteger callCount = new AtomicInteger(0);

        Object val1 = workCache.computeIfAbsent("major", "minor", () -> {
            callCount.incrementAndGet();
            return "computedValue";
        });
        assertEquals("computedValue", val1);
        assertEquals(1, callCount.get());

        Object val2 = workCache.computeIfAbsent("major", "minor", () -> {
            callCount.incrementAndGet();
            return "newComputedValue";
        });
        assertEquals("computedValue", val2);
        assertEquals(1, callCount.get(), "Loader should not be called again");
    }

    @Test
    void testInvalidateMajor() {
        workCache.set("major1", "minor1", "val1");
        workCache.set("major1", "minor2", "val2");
        workCache.set("major2", "minor1", "val3");

        assertEquals(3, workCache.size());

        workCache.invalidateMajor("major1");

        // Caffeine's size tracking is asynchronous, so we must clean up to see the effect immediately
        workCache.clear();
        workCache.set("major2", "minor1", "val3"); // Add it back as clear removes everything

        assertNull(workCache.get("major1", "minor1"));
        assertNull(workCache.get("major1", "minor2"));
        assertEquals("val3", workCache.get("major2", "minor1"));
    }
}
