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
package st.orm.spring.boot.autoconfigure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import st.orm.EntityCallback;
import st.orm.StormConfig;
import st.orm.core.template.impl.SchemaValidator;
import st.orm.template.ORMTemplate;

/**
 * Auto-configuration for the Storm ORM framework.
 *
 * <p>Creates an {@link ORMTemplate} bean from the available {@link DataSource} if no {@code ORMTemplate} bean has been
 * defined by the user. A {@link StormConfig} is built from the bound {@link StormProperties} and passed to the
 * {@code ORMTemplate} factory.</p>
 *
 * @see StormConfig
 */
@AutoConfiguration
@ConditionalOnClass(ORMTemplate.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(StormProperties.class)
public class StormAutoConfiguration {

    /**
     * Creates an {@link ORMTemplate} bean using the provided {@link DataSource} and {@link StormProperties}.
     *
     * <p>A {@link StormConfig} is built from the bound properties. Fields not explicitly configured in
     * {@code application.yml} fall back to system properties and then to built-in defaults.</p>
     *
     * <p>This bean backs off if the user has already defined their own {@code ORMTemplate} bean.</p>
     *
     * @param dataSource the data source to use for database operations.
     * @param properties the Storm configuration properties bound from {@code storm.*}.
     * @return a new {@link ORMTemplate} instance.
     */
    @Bean
    @ConditionalOnMissingBean(ORMTemplate.class)
    public ORMTemplate ormTemplate(DataSource dataSource, StormProperties properties,
                                   List<EntityCallback<?>> entityCallbacks) {
        return ORMTemplate.of(dataSource, toStormConfig(properties))
                .withEntityCallbacks(entityCallbacks);
    }

    /**
     * Runs schema validation after all singleton beans have been fully initialized. This guarantees that migration
     * tools like Flyway and Liquibase have completed their work before validation occurs.
     */
    @Bean
    SmartInitializingSingleton stormSchemaValidator(DataSource dataSource, StormProperties properties) {
        return () -> {
            String schemaMode = properties.getValidation().getSchemaMode();
            if (schemaMode == null || schemaMode.isBlank() || "none".equalsIgnoreCase(schemaMode.trim())) {
                return;
            }
            SchemaValidator validator = SchemaValidator.of(dataSource);
            if ("fail".equalsIgnoreCase(schemaMode.trim())) {
                validator.validateOrThrow();
            } else if ("warn".equalsIgnoreCase(schemaMode.trim())) {
                validator.validateOrWarn();
            }
        };
    }

    private static StormConfig toStormConfig(StormProperties properties) {
        Map<String, String> map = new HashMap<>();
        var update = properties.getUpdate();
        if (update.getDefaultMode() != null) {
            map.put(StormConfig.UPDATE_DEFAULT_MODE, update.getDefaultMode().trim().toUpperCase());
        }
        if (update.getDirtyCheck() != null) {
            map.put(StormConfig.UPDATE_DIRTY_CHECK, update.getDirtyCheck().trim().toUpperCase());
        }
        if (update.getMaxShapes() != null) {
            map.put(StormConfig.UPDATE_MAX_SHAPES, update.getMaxShapes().toString());
        }
        var entityCache = properties.getEntityCache();
        if (entityCache.getRetention() != null) {
            map.put(StormConfig.ENTITY_CACHE_RETENTION, entityCache.getRetention().trim());
        }
        var templateCache = properties.getTemplateCache();
        if (templateCache.getSize() != null) {
            map.put(StormConfig.TEMPLATE_CACHE_SIZE, templateCache.getSize().toString());
        }
        if (properties.getAnsiEscaping() != null) {
            map.put(StormConfig.ANSI_ESCAPING, properties.getAnsiEscaping().toString());
        }
        var validation = properties.getValidation();
        if (validation.getRecordMode() != null) {
            map.put(StormConfig.VALIDATION_RECORD_MODE, validation.getRecordMode().trim());
        }
        if (validation.getStrict() != null) {
            map.put(StormConfig.VALIDATION_STRICT, validation.getStrict().toString());
        }
        return StormConfig.of(map);
    }
}
