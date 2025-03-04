/*
 * Copyright (c) 2018, 2022 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.common.configurable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.helidon.common.context.ContextAwareExecutorService;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Unit test for {@link ThreadPoolSupplier}.
 */
class ThreadPoolSupplierTest {
    private static ThreadPoolExecutor builtInstance;
    private static ThreadPoolExecutor configuredInstance;
    private static ThreadPoolExecutor defaultInstance;

    private static ThreadPoolSupplier builtSupplier;
    private static ThreadPoolSupplier configuredSupplier;
    private static ThreadPoolSupplier defaultSupplier;


    @BeforeAll
    static void initClass() {
        defaultSupplier = ThreadPoolSupplier.create("test-thread-pool");
        defaultInstance = ensureOurExecutor(defaultSupplier.getThreadPool());

        builtSupplier = ThreadPoolSupplier.builder()
                .threadNamePrefix("thread-pool-unit-test-")
                .corePoolSize(2)
                .daemon(true)
                .prestart(true)
                .queueCapacity(10)
                .build();
        builtInstance = ensureOurExecutor(builtSupplier.getThreadPool());

        configuredSupplier = ThreadPoolSupplier.create(Config.create().get("unit.thread-pool"));
        configuredInstance = ensureOurExecutor(configuredSupplier.getThreadPool());
    }

    private static ThreadPoolExecutor ensureOurExecutor(ExecutorService threadPool) {
        // thread pool should be our implementation, unless Loom virtual threads are used
        assertThat(threadPool, instanceOf(ThreadPoolExecutor.class));
        return (ThreadPoolExecutor) threadPool;
    }

    @Test
    void testDefaultInstance() throws ExecutionException, InterruptedException {
        testInstance(defaultInstance,
                     "helidon-",
                     10,
                     10000,
                     10,
                     true);
        checkObserver(defaultSupplier, defaultInstance, "helidon-");
    }

    @Test
    void testConfiguredInstance() throws ExecutionException, InterruptedException {
        testInstance(configuredInstance,
                     "thread-pool-config-unit-test-",
                     3,
                     15,
                     0,
                     false);
        checkObserver(configuredSupplier, configuredInstance, "thread-pool-config-unit-test-");
    }

    @Test
    void testBuiltInstance() throws ExecutionException, InterruptedException {
        testInstance(builtInstance,
                     "thread-pool-unit-test-",
                     2,
                     10,
                     2,
                     true);
        checkObserver(builtSupplier, builtInstance, "thread-pool-unit-test-");
    }

    @Test
    void testExperimentalConfig() {
        String thresholdKey = "growth-threshold";
        String rateKey = "growth-rate";
        String threshold = "1025";
        String rate = "77";
        Logger log = Logger.getLogger(ThreadPoolSupplier.class.getName());
        List<LogRecord> logRecords = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws SecurityException {
            }
        };

        try {
            log.addHandler(handler);
            Config config = Config.create(ConfigSources.create(Map.of(thresholdKey, threshold, rateKey, rate)));
            ExecutorService executor = ThreadPoolSupplier.create(config, "test-thread-pool").get();
            Optional<ThreadPool> asThreadPool = ThreadPool.asThreadPool(executor);
            ThreadPool pool = asThreadPool.orElseThrow(() -> new RuntimeException("not a thread pool"));
            assertThat(pool.getGrowthThreshold(), is(Integer.parseInt(threshold)));
            assertThat(pool.getGrowthRate(), is(Integer.parseInt(rate)));
            assertThat(logRecords.size(), is(2));
            if (logRecords.get(0).getMessage().contains(thresholdKey)) {
                assertThat(logRecords.get(0).getMessage(), containsString("growth-threshold\" is EXPERIMENTAL"));
                assertThat(logRecords.get(1).getMessage(), containsString("growth-rate\" is EXPERIMENTAL"));
            } else {
                assertThat(logRecords.get(1).getMessage(), containsString("growth-threshold\" is EXPERIMENTAL"));
                assertThat(logRecords.get(0).getMessage(), containsString("growth-rate\" is EXPERIMENTAL"));
            }
            pool.shutdown();
        } finally {
            log.removeHandler(handler);
        }
    }

    private void testInstance(ThreadPoolExecutor theInstance,
                              String namePrefix,
                              int corePoolSize,
                              int queueCapacity,
                              int poolSize,
                              boolean shouldBeDaemon) throws ExecutionException, InterruptedException {
        // test that we did indeed create an executor service
        assertThat(theInstance, notNullValue());
        // test that pool size is configured correctly
        assertThat(theInstance.getCorePoolSize(), is(corePoolSize));
        // test that queue-capacity is configured correctly
        assertThat(theInstance.getQueue().remainingCapacity(), is(queueCapacity));
        // test that prestart is configured correctly
        assertThat(theInstance.getPoolSize(), is(poolSize));

        AtomicReference<String> threadName = new AtomicReference<>();
        theInstance
            .submit(() -> threadName.set(Thread.currentThread().getName()))
            .get();

        assertThat(threadName.get(), startsWith(namePrefix));

        AtomicReference<Boolean> isDaemon = new AtomicReference<>();
        theInstance
            .submit(() -> isDaemon.set(Thread.currentThread().isDaemon()))
            .get();

        assertThat(isDaemon.get(), is(shouldBeDaemon));
    }

    private void checkObserver(ThreadPoolSupplier supplier, ExecutorService theInstance, String name) {
        assertThat("Supplier collection",
                   ObserverForTesting.instance.suppliers(),
                   hasKey(supplier));
        ObserverForTesting.SupplierInfo supplierInfo = ObserverForTesting.instance.suppliers().get(supplier);
        assertThat("ExecutorService",
                   supplierInfo.context().executorServices(),
                   hasItem(theInstance instanceof ContextAwareExecutorService
                                   ? ((ContextAwareExecutorService) theInstance).unwrap()
                                   : theInstance));
        ObserverForTesting.Context ctx = supplierInfo.context();
        assertThat("Count of non-scheduled executor services registered", ctx.threadPoolCount(), CoreMatchers.is(not(0)));
        assertThat("Count of scheduled executor services registered", ctx.scheduledCount(), CoreMatchers.is(0));
    }
}
