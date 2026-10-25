package st.orm.core.graal;

import st.orm.GenerateMetamodel;

/**
 * Query result shape: how many pets live in an audited city. Listed in the result type index of the test classes,
 * with a component of a data type.
 */
@GenerateMetamodel
public record AuditedCityPetCount(AuditedCity city, long pets) {}
