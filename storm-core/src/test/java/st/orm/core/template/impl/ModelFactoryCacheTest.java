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

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import st.orm.SqlTemplateException;
import st.orm.core.model.City;

/**
 * Models are cached per builder, and a builder with a resolver created per template equals no other. The cache keeps
 * a bounded number of builders per record type, so templates created on demand do not add models without end.
 */
public class ModelFactoryCacheTest {

    /**
     * A builder whose resolver captures a value, so that each call yields a resolver equal to no other, as a resolver
     * created per tenant would be.
     */
    private static ModelBuilderImpl builderWithOwnResolver(String tenant) {
        return (ModelBuilderImpl) new ModelBuilderImpl().schemaResolver((type, schema) -> tenant);
    }

    @Test
    void equalBuildersShareTheirModels() throws SqlTemplateException {
        assertSame(
                ModelFactory.getModel(new ModelBuilderImpl(), City.class, true),
                ModelFactory.getModel(new ModelBuilderImpl(), City.class, true));
    }

    @Test
    void theModelsOfABuilderNoLongerInUseAreEvicted() throws SqlTemplateException {
        var first = builderWithOwnResolver("first");
        var model = ModelFactory.getModel(first, City.class, true);
        assertSame(model, ModelFactory.getModel(first, City.class, true));
        for (int i = 0; i < 1_000; i++) {
            ModelFactory.getModel(builderWithOwnResolver("tenant_" + i), City.class, true);
        }
        assertNotSame(model, ModelFactory.getModel(first, City.class, true));
    }
}
