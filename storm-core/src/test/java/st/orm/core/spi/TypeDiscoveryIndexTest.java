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
package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.core.AutoConverter;
import st.orm.core.model.City;
import st.orm.core.model.Country;
import st.orm.core.model.OwnerPetCount;

/**
 * The type index is written by the metamodel processor during this module's test compilation, so these
 * tests observe the real processor output. Java entities are records, so the Data index must contain the
 * record entities of the test model.
 */
class TypeDiscoveryIndexTest {

    @Test
    void indexIsAvailableWhenTheProcessorRan() {
        assertTrue(TypeDiscovery.isIndexAvailable());
    }

    @Test
    void dataIndexContainsRecordEntities() {
        List<Class<? extends Data>> dataTypes = TypeDiscovery.getDataTypes();
        assertTrue(dataTypes.contains(City.class), "record entity");
        assertTrue(dataTypes.contains(Country.class), "record entity");
    }

    @Test
    void resultIndexContainsTheRecordsAnnotatedGenerateMetamodel() {
        List<Class<?>> resultTypes = TypeDiscovery.getResultTypes();
        assertTrue(resultTypes.contains(OwnerPetCount.class), "result row of a custom query");
        assertFalse(resultTypes.contains(City.class), "a Data type is listed in the Data index only");
    }

    @Test
    void converterIndexContainsConverterClasses() {
        assertTrue(TypeDiscovery.getConverterTypes().contains(AutoConverter.class));
    }

    @Test
    void indexIsUnavailableWithoutIndexResources() {
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(new URLClassLoader(new URL[0], null));
        try {
            assertFalse(TypeDiscovery.isIndexAvailable());
            assertEquals(List.of(), TypeDiscovery.getDataTypes());
        } finally {
            currentThread.setContextClassLoader(originalClassLoader);
        }
    }
}
