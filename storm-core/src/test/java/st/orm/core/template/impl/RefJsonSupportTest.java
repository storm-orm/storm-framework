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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Entity;
import st.orm.PK;
import st.orm.Projection;
import st.orm.Ref;
import st.orm.core.spi.RefFactory;

/**
 * The part of reading and writing a ref as JSON that does not depend on the JSON library: the key type of the
 * target, and the refs created for an id, an entity and a projection.
 */
class RefJsonSupportTest {

    record Owner(@PK Integer id, String name) implements Entity<Integer> {}

    record OwnerSummary(@PK Integer id, String name) implements Projection<Integer> {}

    record Plain(String name) implements Data {}

    record VisitKey(int petId, int day) {}

    record Visit(@PK VisitKey key, String note) implements Entity<VisitKey> {}

    /** A ref factory that counts the refs it creates, standing in for the attached factory of a template. */
    static final class CountingRefFactory implements RefFactory {
        int created;

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Data, ID> Ref<T> create(Class<T> type, ID id) {
            created++;
            return Ref.of((Class) type, id);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Data, ID> Ref<T> create(T record, ID id) {
            return Ref.of((Class) record.getClass(), id);
        }
    }

    @Test
    void theKeyTypeIsThatOfThePkFieldAndNullWithoutOne() {
        assertEquals(Integer.class, RefJsonSupport.pkType(Owner.class));
        assertEquals(Integer.class, RefJsonSupport.pkType(Owner.class), "cached");
        assertEquals(VisitKey.class, RefJsonSupport.pkType(Visit.class));
        assertNull(RefJsonSupport.pkType(Plain.class));
        assertNull(RefJsonSupport.pkType(Plain.class), "cached");
    }

    @Test
    void aRecordKeyIsACompoundKey() {
        assertTrue(RefJsonSupport.isCompoundKey(VisitKey.class));
        assertFalse(RefJsonSupport.isCompoundKey(Integer.class));
    }

    @Test
    void anIntegralIdIsAnIntegerWhenItFitsAndALongOtherwise() {
        assertEquals(42, RefJsonSupport.integralId(42));
        assertEquals(Integer.MAX_VALUE, RefJsonSupport.integralId(Integer.MAX_VALUE));
        assertEquals((long) Integer.MAX_VALUE + 1, RefJsonSupport.integralId((long) Integer.MAX_VALUE + 1));
        assertEquals((long) Integer.MIN_VALUE - 1, RefJsonSupport.integralId((long) Integer.MIN_VALUE - 1));
    }

    @Test
    void anIdBecomesARefThroughTheFactoryWhenThereIsOne() {
        var factory = new CountingRefFactory();
        var attached = RefJsonSupport.refOf(Owner.class, 7, () -> factory);
        assertNotNull(attached);
        assertEquals(7, attached.id());
        assertEquals(1, factory.created);
        var detached = RefJsonSupport.refOf(Owner.class, 7, null);
        assertNotNull(detached);
        assertEquals(7, detached.id());
        var suppliedNothing = RefJsonSupport.refOf(Owner.class, 7, () -> null);
        assertNotNull(suppliedNothing);
        assertEquals(7, suppliedNothing.id());
        assertNull(RefJsonSupport.refOf(Owner.class, null, () -> factory));
        assertEquals(1, factory.created, "no ref for no id");
    }

    @Test
    void anEntityBecomesALoadedRefAndAnythingElseIsRefused() {
        var owner = new Owner(1, "Betty");
        var ref = RefJsonSupport.loadedRef(owner);
        assertNotNull(ref);
        assertSame(owner, ref.getOrNull());
        assertNull(RefJsonSupport.loadedRef((Data) null));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> RefJsonSupport.loadedRef(new OwnerSummary(1, "Betty")));
        assertEquals("@entity must deserialize to an Entity", thrown.getMessage());
    }

    @Test
    void aProjectionBecomesALoadedRefWithTheIdItWasReadWith() {
        var summary = new OwnerSummary(1, "Betty");
        var ref = RefJsonSupport.loadedRef(OwnerSummary.class, summary, 1);
        assertNotNull(ref);
        assertSame(summary, ref.getOrNull());
        assertEquals(1, ref.id());
        var owner = new Owner(2, "George");
        var entityRef = RefJsonSupport.loadedRef(Owner.class, owner, 2);
        assertNotNull(entityRef);
        assertSame(owner, entityRef.getOrNull());
        var plain = RefJsonSupport.loadedRef(Plain.class, new Plain("x"), 3);
        assertNotNull(plain);
        assertEquals(3, plain.id());
        assertNull(plain.getOrNull(), "plain data cannot be loaded into a ref; its id is kept");
        assertNull(RefJsonSupport.loadedRef(Plain.class, null, 3));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> RefJsonSupport.loadedRef(Plain.class, new Plain("x"), null));
        assertEquals("@projection requires a non-null @id", thrown.getMessage());
    }
}
