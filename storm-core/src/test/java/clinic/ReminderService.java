/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package clinic;

import static st.orm.TransactionPropagation.REQUIRED;

import st.orm.TransactionOptions;
import st.orm.core.spi.TransactionRunner;

/**
 * An application class outside the framework's packages, so the call site walker names its methods: each runs a
 * transaction of its own around the work it is given.
 */
public final class ReminderService {

    private static final TransactionOptions OPTIONS = new TransactionOptions(REQUIRED, null, null, null);

    private final Runnable work;

    public ReminderService(Runnable work) {
        this.work = work;
    }

    public void remindOwners() {
        TransactionRunner.<Void, RuntimeException>execute(OPTIONS, transaction -> {
            work.run();
            return null;
        });
    }

    public void remindVets() {
        TransactionRunner.<Void, RuntimeException>execute(OPTIONS, transaction -> {
            work.run();
            return null;
        });
    }

    /** Looks the owners up one at a time: a loop of its own, at a line of its own. */
    public static void lookUpOwners(CustomerRepository customers) {
        for (int id = 1; id <= 10; id++) {
            customers.lookUp(id);
        }
    }

    /** Looks the vets up one at a time: another loop, at another line. */
    public static void lookUpVets(CustomerRepository customers) {
        for (int id = 1; id <= 12; id++) {
            customers.lookUp(id);
        }
    }

    /** Looks one customer up, from a line of its own. */
    public static void lookUpOne(CustomerRepository customers, int id) {
        customers.lookUp(id);
    }
}
