package st.orm.template.model

import st.orm.DbTable
import st.orm.Discriminator
import st.orm.Entity
import st.orm.FK
import st.orm.PK
import st.orm.Ref
import java.time.LocalDate

/**
 * A single-table hierarchy whose subtypes declare foreign keys: every treatment references the pet, a checkup may
 * reference the vet, and a surgery references the surgeon.
 */
@Discriminator(column = "kind")
@DbTable("treatment")
internal sealed interface Treatment : Entity<Int> {
    val id: Int
    val pet: Pet
    val treatmentDate: LocalDate
}

internal data class Checkup(
    @PK override val id: Int = 0,
    @FK override val pet: Pet,
    override val treatmentDate: LocalDate,
    @FK val vet: Ref<Vet>?,
    val notes: String,
) : Treatment

internal data class Surgery(
    @PK override val id: Int = 0,
    @FK override val pet: Pet,
    override val treatmentDate: LocalDate,
    @FK val surgeon: Vet,
    val duration: Int,
) : Treatment
