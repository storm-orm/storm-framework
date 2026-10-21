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
package st.orm.spring.boot;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import st.orm.StormConfig;

/**
 * Configuration properties for the Storm ORM framework.
 *
 * <p>These properties are bound from the {@code storm.*} namespace in {@code application.yml} or
 * {@code application.properties}. The auto-configuration builds a {@link st.orm.StormConfig} from these properties
 * and passes it to the {@code ORMTemplate} factory.</p>
 *
 * <p>Example configuration:</p>
 * <pre>{@code
 * storm:
 *   ansi-escaping: false
 *   update:
 *     default-mode: ENTITY
 *     dirty-check: INSTANCE
 *     max-shapes: 5
 * }</pre>
 *
 * @see st.orm.StormConfig
 */
@ConfigurationProperties(prefix = "storm")
public class StormProperties {

    /** Update behavior configuration. */
    private Update update = new Update();

    /** Entity cache configuration. */
    private EntityCache entityCache = new EntityCache();

    /** Template cache configuration. */
    private TemplateCache templateCache = new TemplateCache();

    /** Validation configuration. */
    private Validation validation = new Validation();

    /** Exception translation configuration. */
    private ExceptionTranslation exceptionTranslation = new ExceptionTranslation();

    /** Query observation configuration. */
    private Observations observations = new Observations();

    /** Tracing configuration. */
    private Tracing tracing = new Tracing();

    /** Per-request SQL log configuration. */
    private SqlLog sqlLog = new SqlLog();

    /** Statement advice configuration. */
    private Advice advice = new Advice();

    /** Whether to enable ANSI escape sequences in Storm's log output. */
    private Boolean ansiEscaping;

    /** Returns the update behavior configuration. */
    public Update getUpdate() { return update; }

    /** Sets the update behavior configuration. */
    public void setUpdate(Update update) { this.update = update; }

    /** Returns the entity cache configuration. */
    public EntityCache getEntityCache() { return entityCache; }

    /** Sets the entity cache configuration. */
    public void setEntityCache(EntityCache entityCache) { this.entityCache = entityCache; }

    /** Returns the template cache configuration. */
    public TemplateCache getTemplateCache() { return templateCache; }

    /** Sets the template cache configuration. */
    public void setTemplateCache(TemplateCache templateCache) { this.templateCache = templateCache; }

    /** Returns the validation configuration. */
    public Validation getValidation() { return validation; }

    /** Returns the exception translation configuration. */
    public ExceptionTranslation getExceptionTranslation() { return exceptionTranslation; }

    /** Returns the query observation configuration. */
    public Observations getObservations() { return observations; }

    /** Returns the tracing configuration. */
    public Tracing getTracing() { return tracing; }

    /** Returns the per-request SQL log configuration. */
    public SqlLog getSqlLog() { return sqlLog; }

    /** Sets the per-request SQL log configuration. */
    public void setSqlLog(SqlLog sqlLog) { this.sqlLog = sqlLog; }

    /** Returns the statement advice configuration. */
    public Advice getAdvice() { return advice; }

    /** Sets the statement advice configuration. */
    public void setAdvice(Advice advice) { this.advice = advice; }

    /** Sets the tracing configuration. */
    public void setTracing(Tracing tracing) { this.tracing = tracing; }

    /** Sets the query observation configuration. */
    public void setObservations(Observations observations) { this.observations = observations; }

    /** Sets the exception translation configuration. */
    public void setExceptionTranslation(ExceptionTranslation exceptionTranslation) { this.exceptionTranslation = exceptionTranslation; }

    /** Sets the validation configuration. */
    public void setValidation(Validation validation) { this.validation = validation; }

    /** Returns whether ANSI escape sequences are enabled. */
    public Boolean getAnsiEscaping() { return ansiEscaping; }

    /** Sets whether ANSI escape sequences are enabled. */
    public void setAnsiEscaping(Boolean ansiEscaping) { this.ansiEscaping = ansiEscaping; }

    /**
     * Configuration properties for Storm's update behavior.
     *
     * <p>Mapped to the {@code storm.update.*} namespace.</p>
     */
    public static class Update {

        /** The default update mode ({@code ENTITY}, {@code FIELD}, or {@code OFF}). */
        private String defaultMode;

        /** The dirty-check strategy ({@code INSTANCE} or {@code FIELD}). */
        private String dirtyCheck;

        /** The maximum number of update shapes to cache. */
        private @Nullable Integer maxShapes;

        /** Returns the default update mode. */
        public String getDefaultMode() { return defaultMode; }

        /** Sets the default update mode. */
        public void setDefaultMode(String defaultMode) { this.defaultMode = defaultMode; }

        /** Returns the dirty-check strategy. */
        public String getDirtyCheck() { return dirtyCheck; }

        /** Sets the dirty-check strategy. */
        public void setDirtyCheck(String dirtyCheck) { this.dirtyCheck = dirtyCheck; }

        /** Returns the maximum number of update shapes to cache. */
        public @Nullable Integer getMaxShapes() { return maxShapes; }

        /** Sets the maximum number of update shapes to cache. */
        public void setMaxShapes(@Nullable Integer maxShapes) { this.maxShapes = maxShapes; }
    }

    /**
     * Configuration properties for Storm's entity cache.
     *
     * <p>Mapped to the {@code storm.entity-cache.*} namespace.</p>
     */
    public static class EntityCache {

        /** The cache retention policy ({@code default} or {@code light}). */
        private String retention;

        /** Returns the cache retention policy. */
        public String getRetention() { return retention; }

        /** Sets the cache retention policy. */
        public void setRetention(String retention) { this.retention = retention; }
    }

    /**
     * Configuration properties for Storm's template cache.
     *
     * <p>Mapped to the {@code storm.template-cache.*} namespace.</p>
     */
    public static class TemplateCache {

        /** The maximum number of templates to cache. */
        private @Nullable Integer size;

        /** Returns the maximum number of templates to cache. */
        public @Nullable Integer getSize() { return size; }

        /** Sets the maximum number of templates to cache. */
        public void setSize(@Nullable Integer size) { this.size = size; }
    }

    /**
     * Configuration properties for Storm's validation behavior.
     *
     * <p>Mapped to the {@code storm.validation.*} namespace.</p>
     */
    public static class Validation {

        /**
         * Record validation mode: {@code "fail"} (default), {@code "warn"}, or {@code "none"}.
         *
         * <p>When set to {@code "fail"}, validation errors cause startup to fail with a {@code PersistenceException}.
         * When set to {@code "warn"}, errors are logged as warnings; startup continues.
         * When set to {@code "none"}, record validation is skipped entirely.</p>
         *
         * <p>Defaults to {@code "fail"} when not set. Validation runs after all singleton beans are initialized,
         * so migrations executed by beans such as Flyway or Liquibase complete first.</p>
         */
        private String recordMode;

        /**
         * Schema validation mode: {@code "none"}, {@code "warn"}, or {@code "fail"} (default).
         *
         * <p>When set to {@code "fail"}, schema validation runs at startup and blocks if mismatches are found.
         * When set to {@code "warn"}, mismatches are logged at WARN level but startup continues.
         * When set to {@code "load"}, the schema is loaded without being validated, which Storm uses for advice
         * and optimization; {@code "fail"} and {@code "warn"} load it as well.
         * When set to {@code "none"}, the schema is neither validated nor loaded.</p>
         *
         * <p>Defaults to {@code "fail"} when not set. Validation runs after all singleton beans are initialized,
         * so migrations executed by beans such as Flyway or Liquibase complete first.</p>
         */
        private String schemaMode;

        /** Whether to treat warnings (type narrowing, nullability mismatches) as errors. */
        private Boolean strict;

        /** Returns the record validation mode. */
        public String getRecordMode() { return recordMode; }

        /** Sets the record validation mode. */
        public void setRecordMode(String recordMode) { this.recordMode = recordMode; }

        /** Returns the schema validation mode. */
        public String getSchemaMode() { return schemaMode; }

        /** Sets the schema validation mode. */
        public void setSchemaMode(String schemaMode) { this.schemaMode = schemaMode; }

        /** Returns whether strict validation is enabled. */
        public Boolean getStrict() { return strict; }

        /** Sets whether strict validation is enabled. */
        public void setStrict(Boolean strict) { this.strict = strict; }
    }

    /** Tracing configuration. */
    public static class Tracing {

        /**
         * Whether the current trace context is appended to SQL statements as a sqlcommenter-style comment:
         * "true" comments every statement inside a span, "sampled" comments only statements of sampled
         * traces, "false" (default) disables the comments. A per-execution comment defeats prepared
         * statement caching; prefer "sampled" when the sampling probability is below 1.0.
         */
        private String sqlComments;

        /** Returns the trace context SQL comment mode. */
        public String getSqlComments() { return sqlComments; }

        /** Sets the trace context SQL comment mode. */
        public void setSqlComments(String sqlComments) { this.sqlComments = sqlComments; }
    }

    /**
     * SQL log configuration. The umbrella covers logs that answer different questions about the same
     * executions: the performance log ({@code st.orm.sql.perf}) says what a unit of work cost the database,
     * and the slow statement log ({@code st.orm.sql.slow}) names the single execution that cost too much. Each has
     * its own section; call-site attribution is shared, since both logs name the frame the same way. Statement
     * advice is configured under {@code storm.advice}.
     */
    public static class SqlLog {

        /**
         * Packages whose frames are skipped when attributing an execution to a call site, so rows name the code
         * that asked for the work rather than the application's own database plumbing. Shared: both logs
         * attribute through the same walker.
         */
        private List<String> callSiteSkip = List.of();

        /** Returns the packages skipped in call-site attribution. */
        public List<String> getCallSiteSkip() { return callSiteSkip; }

        /** Sets the packages skipped in call-site attribution. */
        public void setCallSiteSkip(List<String> callSiteSkip) { this.callSiteSkip = callSiteSkip; }

        /** The performance log. */
        private Performance performance = new Performance();

        /** Returns the performance log configuration. */
        public Performance getPerformance() { return performance; }

        /** Sets the performance log configuration. */
        public void setPerformance(Performance performance) { this.performance = performance; }

        /** The slow statement log. */
        private Slow slow = new Slow();

        /** Returns the slow statement log configuration. */
        public Slow getSlow() { return slow; }

        /** Sets the slow statement log configuration. */
        public void setSlow(Slow slow) { this.slow = slow; }

        /**
         * The performance log: what one unit of work cost the database, reported as a single summary under
         * {@code st.orm.sql.perf}. Needs a boundary, which is what {@code enabled} installs.
         */
        public static class Performance {

            /**
             * Whether each unit of work is wrapped in a scope whose summary is logged. Defaults to false.
             *
             * <p>Installs the request filter and the entry-point proxies, so it is read once at startup. The
             * settings that decide what the log reports are read per unit of work and stay changeable while the
             * application runs.</p>
             */
            private boolean enabled;

            /** Returns whether the performance log is enabled. */
            public boolean isEnabled() { return enabled; }

            /** Sets whether the performance log is enabled. */
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            /** Number of statements to record per unit of work; the summary counts the rest regardless. */
            private int limit = 200;

            /** Returns the number of statements recorded per unit of work. */
            public int getLimit() { return limit; }

            /** Sets the number of statements recorded per unit of work. */
            public void setLimit(int limit) { this.limit = limit; }

            /**
             * Whether each execution is attributed to the application frame that caused it, shown per row as
             * {@code @ File.ext:line}. Costs a stack walk per execution while a scope records; suited to
             * development. Defaults to false.
             */
            private boolean callSites;

            /** Returns whether call sites are recorded. */
            public boolean isCallSites() { return callSites; }

            /** Sets whether call sites are recorded. */
            public void setCallSites(boolean callSites) { this.callSites = callSites; }

            /**
             * Width a summary row aims for, such as 120 for narrow viewers or 240 for wide ones; the statement
             * text elides to what the row's other columns leave. A display property of the deployment, applied
             * once at startup.
             */
            private @Nullable Integer lineWidth;

            /** Returns the display width summary rows aim for. */
            public @Nullable Integer getLineWidth() { return lineWidth; }

            /** Sets the display width summary rows aim for. */
            public void setLineWidth(@Nullable Integer lineWidth) { this.lineWidth = lineWidth; }

            /**
             * Annotations that mark a bean method as an entry point to be wrapped in its own scope, covering the
             * ways work enters the application without an HTTP request. Each invocation reports as one summary
             * named after the method ({@code ReportJob.nightly}), with the same thresholds as the per-request
             * filter.
             *
             * <p>Matching is by annotation type name, directly present on the bean method, so a default whose
             * library is absent from the classpath never matches and costs nothing. Setting the property replaces
             * the default list; an empty list turns entry-point wrapping off.</p>
             */
            private List<String> entryPoints = List.of(
                    "org.springframework.scheduling.annotation.Scheduled",
                    "org.springframework.scheduling.annotation.Schedules",
                    "org.springframework.kafka.annotation.KafkaListener",
                    "org.springframework.kafka.annotation.KafkaListeners",
                    "org.springframework.kafka.annotation.KafkaHandler",
                    "org.springframework.amqp.rabbit.annotation.RabbitListener",
                    "org.springframework.amqp.rabbit.annotation.RabbitListeners",
                    "org.springframework.amqp.rabbit.annotation.RabbitHandler",
                    "org.springframework.jms.annotation.JmsListener",
                    "org.springframework.jms.annotation.JmsListeners",
                    "io.awspring.cloud.sqs.annotation.SqsListener");

            /** Returns the annotations that mark a bean method as an entry point. */
            public List<String> getEntryPoints() { return entryPoints; }

            /** Sets the annotations that mark a bean method as an entry point. */
            public void setEntryPoints(List<String> entryPoints) { this.entryPoints = entryPoints; }

            /** Reporting thresholds; with any set, only work that exceeds one is reported. */
            private Threshold threshold = new Threshold();

            /** Returns the reporting thresholds. */
            public Threshold getThreshold() { return threshold; }

            /** Sets the reporting thresholds. */
            public void setThreshold(Threshold threshold) { this.threshold = threshold; }

            /**
             * Reporting thresholds. Without thresholds every unit of work that touches the database is reported,
             * which suits development; with a threshold set, only work that exceeds one is reported, at WARN,
             * which is a guardrail suited to production.
             */
            public static class Threshold {

                /** Number of statements above which a unit of work is reported. */
                private @Nullable Integer statements;

                /** Duration above which a unit of work is reported, such as {@code 500ms}. */
                private @Nullable Duration duration;

                /** Returns the statement threshold. */
                public @Nullable Integer getStatements() { return statements; }

                /** Sets the statement threshold. */
                public void setStatements(@Nullable Integer statements) { this.statements = statements; }

                /** Returns the duration threshold. */
                public @Nullable Duration getDuration() { return duration; }

                /** Sets the duration threshold. */
                public void setDuration(@Nullable Duration duration) { this.duration = duration; }
            }
        }

        /**
         * The slow statement log: the single executions that cost too much, reported under
         * {@code st.orm.sql.slow}. Needs no boundary and sees every execution, on whatever thread it runs.
         */
        public static class Slow {

            /**
             * Database time above which a single statement execution is reported, such as {@code 200ms}: with the
             * statement, its call site and what there is to analyze it by.
             *
             * <p>Unset, it follows {@code performance.threshold.duration} while the performance log is enabled,
             * since a unit of work that exceeds a duration holds at least one execution and a statement threshold
             * no lower than the work's can only fire inside work that reports anyway. Unset with neither, there is
             * no slow log.</p>
             */
            private @Nullable Duration threshold;

            /** Returns the database time above which a statement execution is reported. */
            public @Nullable Duration getThreshold() { return threshold; }

            /** Sets the database time above which a statement execution is reported. */
            public void setThreshold(@Nullable Duration threshold) { this.threshold = threshold; }

            /**
             * Slow statement lines reported per shape per minute before the rest are suppressed and counted, so a
             * degraded database names every shape that suffers without flooding the log with any of them; zero for
             * no limit. Defaults to 5.
             */
            private @Nullable Integer limit;

            /** Returns the slow statement lines reported per shape per minute. */
            public @Nullable Integer getLimit() { return limit; }

            /** Sets the slow statement lines reported per shape per minute. */
            public void setLimit(@Nullable Integer limit) { this.limit = limit; }
        }
    }

    /**
     * Statement advice: what to change about a statement or an entity, reported under {@code st.orm.sql.advice}.
     *
     * <p>Mapped to the {@code storm.advice.*} namespace.</p>
     */
    public static class Advice {

        /**
         * Categories of advice the log reports: "correctness", "performance" and "design". Defaults to
         * correctness, and to all three while Spring Boot DevTools is on the classpath, as it is in development
         * and never in a packaged application.
         */
        private @Nullable List<String> categories;

        /** Returns the categories of advice the log reports. */
        public @Nullable List<String> getCategories() { return categories; }

        /** Sets the categories of advice the log reports. */
        public void setCategories(@Nullable List<String> categories) { this.categories = categories; }

        /** The design budget entities are checked against. */
        private EntityBudget entityBudget = new EntityBudget();

        /** Returns the design budget entities are checked against. */
        public EntityBudget getEntityBudget() { return entityBudget; }

        /** Sets the design budget entities are checked against. */
        public void setEntityBudget(EntityBudget entityBudget) { this.entityBudget = entityBudget; }
    }

    /**
     * The design budget design advice checks entities against: what an entity's read may add beyond its own
     * columns. A read from half the budget is wide, which a slow read of it is advised on.
     */
    public static class EntityBudget {

        /** The most tables a read may join. Defaults to 10. */
        private @Nullable Integer joins;

        /** The most columns a read may select beyond the entity's own. Defaults to 96. */
        private @Nullable Integer columns;

        /** Returns the most tables a read may join. */
        public @Nullable Integer getJoins() { return joins; }

        /** Sets the most tables a read may join. */
        public void setJoins(@Nullable Integer joins) { this.joins = joins; }

        /** Returns the most columns a read may select beyond the entity's own. */
        public @Nullable Integer getColumns() { return columns; }

        /** Sets the most columns a read may select beyond the entity's own. */
        public void setColumns(@Nullable Integer columns) { this.columns = columns; }
    }

    /** Query observation configuration. */
    public static class Observations {

        /**
         * The key-value vocabulary of query observations: "storm" (default) for the storm.* key values,
         * or "otel" to add the OpenTelemetry database client semantic conventions.
         */
        private String semanticConventions;

        /** Returns the semantic conventions of query observations. */
        public String getSemanticConventions() { return semanticConventions; }

        /** Sets the semantic conventions of query observations. */
        public void setSemanticConventions(String semanticConventions) { this.semanticConventions = semanticConventions; }
    }

    /** Exception translation configuration. */
    public static class ExceptionTranslation {

        /** Whether SQL failures are translated to Spring's DataAccessException hierarchy. Defaults to true. */
        private Boolean enabled;

        /** Returns whether exception translation is enabled. */
        public Boolean getEnabled() { return enabled; }

        /** Sets whether exception translation is enabled. */
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Maps these properties onto Storm's configuration.
     *
     * @since 1.13
     */
    public StormConfig toStormConfig() {
        Map<String, String> map = new HashMap<>();
        if (update.getDefaultMode() != null) {
            map.put(StormConfig.UPDATE_DEFAULT_MODE, update.getDefaultMode().trim().toUpperCase());
        }
        if (update.getDirtyCheck() != null) {
            map.put(StormConfig.UPDATE_DIRTY_CHECK, update.getDirtyCheck().trim().toUpperCase());
        }
        if (update.getMaxShapes() != null) {
            map.put(StormConfig.UPDATE_MAX_SHAPES, update.getMaxShapes().toString());
        }
        if (entityCache.getRetention() != null) {
            map.put(StormConfig.ENTITY_CACHE_RETENTION, entityCache.getRetention().trim());
        }
        if (templateCache.getSize() != null) {
            map.put(StormConfig.TEMPLATE_CACHE_SIZE, templateCache.getSize().toString());
        }
        if (ansiEscaping != null) {
            map.put(StormConfig.ANSI_ESCAPING, ansiEscaping.toString());
        }
        if (validation.getRecordMode() != null) {
            map.put(StormConfig.VALIDATION_RECORD_MODE, validation.getRecordMode().trim());
        }
        if (validation.getStrict() != null) {
            map.put(StormConfig.VALIDATION_STRICT, validation.getStrict().toString());
        }
        return StormConfig.of(map);
    }
}
