// The module name carries the database it binds, which javac's module lint flags as a terminal digit.
@SuppressWarnings("module")
module storm.h2 {
    uses st.orm.core.spi.EntityRepositoryProvider;
    requires storm.foundation;
    requires storm.core;
    requires static org.jspecify;
    requires java.sql;
    provides st.orm.core.spi.EntityRepositoryProvider with st.orm.spi.h2.H2EntityRepositoryProviderImpl;
    provides st.orm.core.spi.SqlDialectProvider with st.orm.spi.h2.H2SqlDialectProviderImpl;
}
