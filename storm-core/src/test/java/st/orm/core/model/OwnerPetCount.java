package st.orm.core.model;

import st.orm.GenerateMetamodel;

/**
 * The result row of a custom query, outside the entity graph: how many pets an owner has.
 */
@GenerateMetamodel
public record OwnerPetCount(int ownerId, long pets) {}
