package st.orm.core;

import static java.time.temporal.ChronoUnit.MICROS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.model.CalendarDay;
import st.orm.core.model.ExchangeRate;
import st.orm.core.model.ExchangeRatePk;
import st.orm.core.model.Heartbeat;
import st.orm.core.template.ORMTemplate;

/**
 * Entities keyed on a date or a timestamp round-trip through the repository: a simple {@code LocalDate} key, a
 * compound key with a {@code LocalDate} component, and an {@code Instant} key.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class TemporalPrimaryKeyIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testLocalDateKey() {
        var orm = ORMTemplate.of(dataSource);
        var calendarDays = orm.entity(CalendarDay.class);
        var christmas = LocalDate.of(2026, 12, 25);
        var boxingDay = LocalDate.of(2026, 12, 26);
        calendarDays.insert(List.of(new CalendarDay(christmas, true), new CalendarDay(boxingDay, false)));
        assertEquals(2, calendarDays.count());
        assertTrue(calendarDays.getById(christmas).holiday());
        assertFalse(calendarDays.getById(boxingDay).holiday());
        calendarDays.update(new CalendarDay(boxingDay, true));
        assertTrue(calendarDays.getById(boxingDay).holiday());
        assertEquals(new CalendarDay(christmas, true), calendarDays.ref(christmas).fetch());
        calendarDays.removeById(christmas);
        assertEquals(List.of(boxingDay), calendarDays.findAll().stream().map(CalendarDay::calendarDate).toList());
    }

    @Test
    public void testCompoundKeyWithLocalDate() {
        var orm = ORMTemplate.of(dataSource);
        var exchangeRates = orm.entity(ExchangeRate.class);
        var monday = LocalDate.of(2026, 10, 19);
        var tuesday = LocalDate.of(2026, 10, 20);
        exchangeRates.insert(List.of(
                new ExchangeRate(new ExchangeRatePk("EUR", monday), 1.08),
                new ExchangeRate(new ExchangeRatePk("EUR", tuesday), 1.09),
                new ExchangeRate(new ExchangeRatePk("GBP", monday), 1.27)));
        assertEquals(3, exchangeRates.count());
        assertEquals(1.09, exchangeRates.getById(new ExchangeRatePk("EUR", tuesday)).rate());
        exchangeRates.update(new ExchangeRate(new ExchangeRatePk("GBP", monday), 1.28));
        assertEquals(1.28, exchangeRates.getById(new ExchangeRatePk("GBP", monday)).rate());
        var ref = exchangeRates.ref(new ExchangeRatePk("EUR", monday));
        assertEquals(1.08, ref.fetch().rate());
        exchangeRates.removeByRef(ref);
        assertEquals(2, exchangeRates.count());
        assertEquals(List.of(new ExchangeRatePk("EUR", tuesday), new ExchangeRatePk("GBP", monday)),
                exchangeRates.findAll().stream().map(ExchangeRate::id).sorted(
                        (a, b) -> a.currency().equals(b.currency())
                                ? a.rateDate().compareTo(b.rateDate())
                                : a.currency().compareTo(b.currency())).toList());
    }

    @Test
    public void testInstantKey() {
        var orm = ORMTemplate.of(dataSource);
        var heartbeats = orm.entity(Heartbeat.class);
        var first = Instant.parse("2026-10-23T08:00:00.123456Z");
        var second = first.plusSeconds(30);
        heartbeats.insert(List.of(new Heartbeat(first, "ok"), new Heartbeat(second, "ok")));
        assertEquals("ok", heartbeats.getById(first).status());
        heartbeats.update(new Heartbeat(second, "degraded"));
        assertEquals("degraded", heartbeats.getById(second).status());
        assertEquals(new Heartbeat(first, "ok"), heartbeats.ref(first).fetch());
        heartbeats.removeById(first);
        assertEquals(List.of(second), heartbeats.findAll().stream().map(Heartbeat::recordedAt).toList());
        // A key built from the clock carries the precision the column holds once truncated.
        var now = Instant.now().truncatedTo(MICROS);
        heartbeats.insert(new Heartbeat(now, "ok"));
        assertEquals(now, heartbeats.getById(now).recordedAt());
    }
}
