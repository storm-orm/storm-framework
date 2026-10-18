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
package st.orm.core.model;

import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;

/**
 * Entities whose reads exceed the design budget: {@code Chain0} joins eleven tables through a chain of inline foreign
 * keys, one past the budget. No tables back them; they are read by reflection alone.
 */
public final class DesignModel {

    private DesignModel() {
    }

    /** One join further than {@code Chain0}, read only to check an entity on first use. */
    public record ChainRoot(@PK Integer id, @FK Chain0 next) implements Entity<Integer> {}

    public record Chain0(@PK Integer id, String name, @FK Chain1 next) implements Entity<Integer> {}

    public record Chain1(@PK Integer id, String name, @FK Chain2 next) implements Entity<Integer> {}

    public record Chain2(@PK Integer id, String name, @FK Chain3 next) implements Entity<Integer> {}

    public record Chain3(@PK Integer id, String name, @FK Chain4 next) implements Entity<Integer> {}

    public record Chain4(@PK Integer id, String name, @FK Chain5 next) implements Entity<Integer> {}

    public record Chain5(@PK Integer id, String name, @FK Chain6 next) implements Entity<Integer> {}

    public record Chain6(@PK Integer id, String name, @FK Chain7 next) implements Entity<Integer> {}

    public record Chain7(@PK Integer id, String name, @FK Chain8 next) implements Entity<Integer> {}

    public record Chain8(@PK Integer id, String name, @FK Chain9 next) implements Entity<Integer> {}

    public record Chain9(@PK Integer id, String name, @FK Chain10 next) implements Entity<Integer> {}

    public record Chain10(@PK Integer id, String name, @FK Chain11 next) implements Entity<Integer> {}

    public record Chain11(@PK Integer id, String name) implements Entity<Integer> {}
}
