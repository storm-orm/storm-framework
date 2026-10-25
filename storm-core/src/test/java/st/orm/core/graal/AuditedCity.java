package st.orm.core.graal;

import st.orm.DbTable;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.EntityCallbacks;
import st.orm.PK;

/**
 * A city whose writes run a declared callback, listed in the type index of the test classes.
 */
@EntityCallbacks(AuditedCity.Audit.class)
@DbTable("city")
public record AuditedCity(@PK Integer id, String name) implements Entity<Integer> {

    public static class Audit implements EntityCallback<AuditedCity> {}
}
