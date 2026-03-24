package st.orm.ktor.model

import st.orm.repository.EntityRepository

interface PetRepository : EntityRepository<Pet, Int>
