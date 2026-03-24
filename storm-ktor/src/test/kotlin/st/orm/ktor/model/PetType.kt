package st.orm.ktor.model

import st.orm.Entity
import st.orm.GenerationStrategy
import st.orm.PK

@JvmRecord
data class PetType(
    @PK(generation = GenerationStrategy.NONE) val id: Int = 0,
    val name: String,
) : Entity<Int>
