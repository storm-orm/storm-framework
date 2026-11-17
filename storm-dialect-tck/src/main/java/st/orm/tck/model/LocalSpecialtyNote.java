package st.orm.tck.model;

import static st.orm.GenerationStrategy.NONE;

import java.time.LocalDateTime;
import lombok.Builder;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;

/** The {@code specialty_note} row with its temporal column read as a date and time without a zone. */
@Builder(toBuilder = true)
@DbTable("specialty_note")
public record LocalSpecialtyNote(
        @PK(generation = NONE) @FK Specialty specialty,
        String note,
        LocalDateTime updatedAt
) implements Entity<Specialty> {}
