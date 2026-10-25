package st.orm.core.model;

import st.orm.GenerateMetamodel;

/**
 * The result row of a custom query, outside the entity graph: how many visits a pet had.
 */
@GenerateMetamodel
public record PetVisitCount(int petId, long visits) {}
