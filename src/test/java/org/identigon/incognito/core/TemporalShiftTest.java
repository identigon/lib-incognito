package org.identigon.incognito.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dconneely.alterego.AlterEgo;
import io.github.dconneely.alterego.Transformation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link TableTransformLoadStage#shiftTemporalOrNull} across every temporal type. The JDBC
 * read path only ever yields {@code java.sql.Date}/{@code java.sql.Timestamp} (via
 * {@code rs.getObject}), so the {@code LocalDateTime} and {@code Instant} branches are unreachable
 * end-to-end — this exercises them directly, verifying type preservation, that the time-of-day is
 * kept (seconds-shift 0), and that {@code Instant} is handled via {@code shiftDateTime} at UTC.
 */
class TemporalShiftTest {

    private static final int WINDOW = 1825; // the SYNTHESISE ±5y window

    private final AlterEgo ae = AlterEgo.builder()
        .salt("incognito-temporal-shift-test-salt".getBytes()).build();
    private final Transformation<LocalDate> dateT = ae.shiftDate(WINDOW);
    private final Transformation<LocalDateTime> dateTimeT = ae.shiftDateTime(WINDOW, 0);

    @Test
    void shiftsEveryTemporalTypePreservingTypeAndTimeOfDay() {
        // LocalDate → LocalDate
        assertInstanceOf(LocalDate.class,
            TableTransformLoadStage.shiftTemporalOrNull(LocalDate.of(1980, 6, 15), dateT, dateTimeT));

        // java.sql.Date → java.sql.Date
        assertInstanceOf(java.sql.Date.class,
            TableTransformLoadStage.shiftTemporalOrNull(java.sql.Date.valueOf("1980-06-15"), dateT, dateTimeT));

        // java.sql.Timestamp → java.sql.Timestamp, time-of-day preserved
        Timestamp ts = Timestamp.valueOf("1980-06-15 14:30:45");
        Object shiftedTs = TableTransformLoadStage.shiftTemporalOrNull(ts, dateT, dateTimeT);
        assertInstanceOf(Timestamp.class, shiftedTs);
        assertEquals(LocalTime.of(14, 30, 45), ((Timestamp) shiftedTs).toLocalDateTime().toLocalTime(),
            "Timestamp time-of-day preserved");

        // LocalDateTime → LocalDateTime, time-of-day preserved
        LocalDateTime ldt = LocalDateTime.of(1980, 6, 15, 9, 5, 30);
        Object shiftedLdt = TableTransformLoadStage.shiftTemporalOrNull(ldt, dateT, dateTimeT);
        assertInstanceOf(LocalDateTime.class, shiftedLdt);
        assertEquals(LocalTime.of(9, 5, 30), ((LocalDateTime) shiftedLdt).toLocalTime(),
            "LocalDateTime time-of-day preserved");

        // Instant → Instant, shifted by a whole number of days (via shiftDateTime at UTC)
        Instant inst = Instant.parse("1980-06-15T14:30:45Z");
        Object shiftedInst = TableTransformLoadStage.shiftTemporalOrNull(inst, dateT, dateTimeT);
        assertInstanceOf(Instant.class, shiftedInst);
        long deltaSeconds = Duration.between(inst, (Instant) shiftedInst).getSeconds();
        assertEquals(0, deltaSeconds % 86_400,
            "Instant shifted by whole days only — time-of-day preserved");

        // Non-temporal → null (caller falls back, e.g. to string fabrication)
        assertNull(TableTransformLoadStage.shiftTemporalOrNull("not-a-date", dateT, dateTimeT));

        // Deterministic: the same input yields the same shifted value.
        assertEquals(shiftedInst, TableTransformLoadStage.shiftTemporalOrNull(inst, dateT, dateTimeT));
    }
}
