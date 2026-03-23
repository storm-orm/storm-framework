module storm.h2 {
    uses st.orm.core.spi.EntityRepositoryProvider;
    requires storm.foundation;
    requires storm.core;
    requires jakarta.annotation;
    requires java.sql;
    provides st.orm.core.spi.EntityRepositoryProvider with st.orm.spi.h2.H2EntityRepositoryProviderImpl;
    provides st.orm.core.spi.SqlDialectProvider with st.orm.spi.h2.H2SqlDialectProviderImpl;
}
