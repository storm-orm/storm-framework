package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.EQUALS;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.Templates.param;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.IntegrationConfig;
import st.orm.core.model.Appointment;
import st.orm.core.model.Appointment_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;

/**
 * Integration tests targeting uncovered branches in PreparedStatementTemplateImpl.setParameters,
 * PreparedStatementTemplateImpl.createConnectionProcessor, and related code paths.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class PreparedStatementParameterTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testShortParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        short value = 7;
        try (var query = orm.query(raw("SELECT CAST(\0 AS SMALLINT)", param(value))).prepare();
             var stream = query.getResultStream(Short.class)) {
            assertEquals(value, stream.findFirst().orElseThrow());
        }
    }

    @Test
    public void testByteParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        byte value = 42;
        try (var query = orm.query(raw("SELECT CAST(\0 AS TINYINT)", param(value))).prepare();
             var stream = query.getResultStream(Byte.class)) {
            assertEquals(value, stream.findFirst().orElseThrow());
        }
    }

    @Test
    public void testFloatParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        float value = 1.5f;
        try (var query = orm.query(raw("SELECT CAST(\0 AS REAL)", param(value))).prepare();
             var stream = query.getResultStream(Float.class)) {
            assertEquals(value, stream.findFirst().orElseThrow(), 0.001f);
        }
    }

    record BigDecimalResult(BigDecimal value) {}

    @Test
    public void testBigDecimalParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        BigDecimal value = new BigDecimal("99.99");
        try (var query = orm.query(raw("SELECT CAST(\0 AS DECIMAL(10,2))", param(value))).prepare();
             var stream = query.getResultStream(BigDecimalResult.class)) {
            BigDecimal result = stream.findFirst().orElseThrow().value();
            assertEquals(0, value.compareTo(result));
        }
    }

    @Test
    public void testByteBufferParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        try (var query = orm.query(raw("SELECT \0", param(buffer))).prepare();
             var stream = query.getResultStream()) {
            Object[] row = stream.findFirst().orElseThrow();
            assertNotNull(row[0]);
        }
    }

    record TimeResult(LocalTime value) {}

    @Test
    public void testTimeParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        Time time = Time.valueOf("14:30:00");
        try (var query = orm.query(raw("SELECT CAST(\0 AS TIME)", param(time))).prepare();
             var stream = query.getResultStream(TimeResult.class)) {
            LocalTime result = stream.findFirst().orElseThrow().value();
            assertEquals(14, result.getHour());
            assertEquals(30, result.getMinute());
        }
    }

    record LocalTimeResult(LocalTime value) {}

    @Test
    public void testLocalTimeParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        LocalTime localTime = LocalTime.of(14, 30, 0);
        try (var query = orm.query(raw("SELECT CAST(\0 AS TIME)", param(localTime))).prepare();
             var stream = query.getResultStream(LocalTimeResult.class)) {
            LocalTime result = stream.findFirst().orElseThrow().value();
            assertEquals(14, result.getHour());
            assertEquals(30, result.getMinute());
        }
    }

    record LocalDateTimeResult(LocalDateTime value) {}

    @Test
    public void testLocalDateTimeParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        LocalDateTime dateTime = LocalDateTime.of(2023, 6, 15, 10, 30);
        try (var query = orm.query(raw("SELECT CAST(\0 AS TIMESTAMP)", param(dateTime))).prepare();
             var stream = query.getResultStream(LocalDateTimeResult.class)) {
            LocalDateTime result = stream.findFirst().orElseThrow().value();
            assertEquals(2023, result.getYear());
            assertEquals(6, result.getMonthValue());
        }
    }

    record StoredText(String value) {}

    @Test
    public void testLocalDateTimeIsStoredAsWrittenInAnyDefaultTimeZone() {
        var orm = ORMTemplate.of(dataSource);
        var appointments = orm.entity(Appointment.class);
        var written = LocalDateTime.of(2026, 6, 15, 12, 0);
        var defaultTimeZone = TimeZone.getDefault();
        for (String zone : List.of("UTC", "America/New_York", "Asia/Tokyo")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            try {
                var id = appointments.insertAndFetchId(new Appointment(null, zone, written));
                assertEquals(written, appointments.getById(id).scheduledAt(), zone);
                assertEquals(1, appointments.select().where(Appointment_.scheduledAt, EQUALS, written)
                        .where(Appointment_.description, EQUALS, zone).getResultCount(), zone);
                try (var query = orm.query(raw("SELECT CAST(scheduled_at AS VARCHAR) FROM appointment WHERE id = \0", param(id))).prepare();
                     var stream = query.getResultStream(StoredText.class)) {
                    assertEquals("2026-06-15 12:00:00", stream.findFirst().orElseThrow().value(), zone);
                }
            } finally {
                TimeZone.setDefault(defaultTimeZone);
            }
        }
    }

    @DbTable("sql_temporal")
    record SqlTemporal(@PK(generation = NONE) Integer id, Date sqlDate, Time sqlTime, Timestamp sqlTimestamp,
                       LocalTime localTime) implements Entity<Integer> {}

    @Test
    public void testSqlDateAndTimeFieldsAreBoundAsTheyAre() {
        var orm = ORMTemplate.of(dataSource);
        orm.query(raw("CREATE TABLE sql_temporal (id integer PRIMARY KEY, sql_date date, sql_time time, sql_timestamp timestamp(9), local_time time(3))")).executeUpdate();
        try {
            var timestamp = Timestamp.valueOf("2026-06-15 23:34:56.123456789");
            var written = new SqlTemporal(1, Date.valueOf("2026-06-15"), Time.valueOf("23:34:56"), timestamp,
                    LocalTime.of(23, 34, 56, 789_000_000));
            var bound = new ArrayList<Object>();
            observe(sql -> sql.parameters().forEach(parameter -> bound.add(parameter.dbValue())),
                    () -> orm.entity(SqlTemporal.class).insert(written));
            // A date or a time bound as a timestamp would be stored as the UTC date and time of its instant.
            assertEquals(List.of(1, written.sqlDate(), written.sqlTime(), timestamp, written.localTime()), bound);
            assertEquals(Date.class, bound.get(1).getClass());
            assertEquals(Time.class, bound.get(2).getClass());
            assertEquals(123456789, ((Timestamp) bound.get(3)).getNanos());
            assertEquals(written, orm.entity(SqlTemporal.class).getById(1));
        } finally {
            orm.query(raw("DROP TABLE sql_temporal")).executeUpdate();
        }
    }

    record OffsetDateTimeResult(OffsetDateTime value) {}

    @Test
    public void testOffsetDateTimeParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        OffsetDateTime offsetDateTime = OffsetDateTime.of(2023, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        try (var query = orm.query(raw("SELECT CAST(\0 AS TIMESTAMP WITH TIME ZONE)", param(offsetDateTime))).prepare();
             var stream = query.getResultStream(OffsetDateTimeResult.class)) {
            OffsetDateTime result = stream.findFirst().orElseThrow().value();
            assertNotNull(result);
            assertEquals(2023, result.getYear());
        }
    }

    record ZonedDateTimeResult(ZonedDateTime value) {}

    @Test
    public void testZonedDateTimeParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        ZonedDateTime zonedDateTime = ZonedDateTime.of(2023, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        try (var query = orm.query(raw("SELECT CAST(\0 AS TIMESTAMP WITH TIME ZONE)", param(zonedDateTime))).prepare();
             var stream = query.getResultStream(ZonedDateTimeResult.class)) {
            ZonedDateTime result = stream.findFirst().orElseThrow().value();
            assertNotNull(result);
            assertEquals(2023, result.getYear());
        }
    }

    record InstantResult(Instant value) {}

    @Test
    public void testInstantParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        Instant instant = Instant.parse("2023-06-15T10:30:00Z");
        try (var query = orm.query(raw("SELECT CAST(\0 AS TIMESTAMP WITH TIME ZONE)", param(instant))).prepare();
             var stream = query.getResultStream(InstantResult.class)) {
            Instant result = stream.findFirst().orElseThrow().value();
            assertNotNull(result);
        }
    }

    enum TestColor { RED, GREEN, BLUE }

    @Test
    public void testEnumParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        try (var query = orm.query(raw("SELECT \0", param(TestColor.RED))).prepare();
             var stream = query.getResultStream(String.class)) {
            assertEquals("RED", stream.findFirst().orElseThrow());
        }
    }

    @Test
    public void testNullParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        try (var query = orm.query(raw("SELECT COALESCE(\0, 'default')", param(null))).prepare();
             var stream = query.getResultStream(String.class)) {
            assertEquals("default", stream.findFirst().orElseThrow());
        }
    }

    @Test
    public void testBooleanParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        try (var query = orm.query(raw("SELECT \0", param(true))).prepare();
             var stream = query.getResultStream(Boolean.class)) {
            assertTrue(stream.findFirst().orElseThrow());
        }
    }

    @Test
    public void testLocalDateParameterBinding() {
        var orm = ORMTemplate.of(dataSource);
        LocalDate date = LocalDate.of(2020, 9, 7);
        try (var query = orm.query(raw("SELECT name FROM pet WHERE birth_date = \0", param(date))).prepare();
             var stream = query.getResultStream(String.class)) {
            assertEquals("Leo", stream.findFirst().orElseThrow());
        }
    }

    @Test
    public void testConnectionBasedTemplateSimpleQuery() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatementTemplate template = new PreparedStatementTemplateImpl(connection);
            var orm = template.toORM();
            long count = orm.selectFrom(st.orm.core.model.City.class).getResultCount();
            assertEquals(6, count);
        }
    }

    @Test
    public void testConnectionBasedTemplateWithParameters() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatementTemplate template = new PreparedStatementTemplateImpl(connection);
            var orm = template.toORM();
            record StringResult(String value) {}
            StringResult result = orm.query(raw("SELECT name FROM city WHERE id = \0", param(1)))
                    .getSingleResult(StringResult.class);
            assertEquals("Sun Paririe", result.value());
        }
    }

    @Test
    public void testWithProviderFilter() {
        var template = new PreparedStatementTemplateImpl(dataSource);
        var filtered = template.withProviderFilter(provider -> true);
        assertNotNull(filtered);
        assertNotNull(filtered.toORM());
    }

    @Test
    public void testUnsafeDeleteRejected() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () ->
                orm.query(raw("DELETE FROM city")).executeUpdate());
    }

    @Test
    public void testUnsafeDeleteAllowed() {
        var orm = ORMTemplate.of(dataSource);
        assertDoesNotThrow(() ->
                orm.query(raw("DELETE FROM pet_extension")).unsafe().executeUpdate());
    }
}
