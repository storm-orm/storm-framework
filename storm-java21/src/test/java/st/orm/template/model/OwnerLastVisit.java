package st.orm.template.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * An owner with the date of the latest visit of their pets, a value a correlated subquery computes per owner.
 */
public record OwnerLastVisit(Owner owner, @Nullable LocalDate lastVisit) {}
