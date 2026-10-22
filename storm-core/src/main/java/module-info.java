// The qualified exports below target sibling modules that depend on storm-core, so they are never
// observable while storm-core itself compiles; suppress the resulting "module not found" warnings.
@SuppressWarnings("module")
module storm.core {
    uses st.orm.core.spi.ORMReflectionProvider;
    uses st.orm.core.spi.ORMConverterProvider;
    uses st.orm.core.spi.EntityRepositoryProvider;
    uses st.orm.core.spi.ProjectionRepositoryProvider;
    uses st.orm.core.spi.QueryBuilderProvider;
    uses st.orm.core.spi.SqlDialectProvider;
    uses st.orm.core.spi.ConnectionProvider;
    uses st.orm.core.spi.TransactionTemplateProvider;
    uses st.orm.core.spi.ExternalTransactionProvider;
    uses st.orm.spi.CursorCodecProvider;
    uses st.orm.mapping.Instantiator;
    exports st.orm.core.template;
    // The impl packages are reachable by Storm's own modules only; to everyone else they are not API.
    exports st.orm.core.template.impl to
            storm.foundation,
            storm.h2,
            storm.jackson2,
            storm.jackson3,
            storm.java,
            storm.kotlin,
            storm.ktor,
            storm.mariadb,
            storm.mssqlserver,
            storm.mysql,
            storm.oracle,
            storm.postgresql,
            storm.serialization,
            storm.spring,
            storm.sqlite,
            storm.test;
    exports st.orm.core.spi;
    // The repository engine surface exists to build the Java and Kotlin facades and the dialect overrides;
    // applications program against the facade repositories, so the package is not application API.
    exports st.orm.core.repository to
            storm.h2,
            storm.jackson2,
            storm.jackson3,
            storm.java,
            storm.kotlin,
            storm.kotlin.spring,
            storm.ktor,
            storm.mariadb,
            storm.micrometer,
            storm.mssqlserver,
            storm.mysql,
            storm.oracle,
            storm.postgresql,
            storm.serialization,
            storm.spring,
            storm.sqlite,
            storm.test;
    exports st.orm.core.repository.impl to
            storm.h2,
            storm.kotlin,
            storm.mariadb,
            storm.mssqlserver,
            storm.mysql,
            storm.oracle,
            storm.postgresql,
            storm.sqlite;
    requires java.management;
    requires java.sql;
    requires static jakarta.persistence;
    requires static org.graalvm.nativeimage;
    requires static org.jspecify;
    requires java.compiler;
    requires storm.foundation;
    requires org.slf4j;
    provides st.orm.core.spi.ORMReflectionProvider with st.orm.core.spi.DefaultORMReflectionProviderImpl;
    provides st.orm.core.spi.EntityRepositoryProvider with st.orm.core.spi.DefaultEntityRepositoryProviderImpl;
    provides st.orm.core.spi.ORMConverterProvider with st.orm.core.spi.DefaultORMConverterProviderImpl;
    provides st.orm.core.spi.ProjectionRepositoryProvider with st.orm.core.spi.DefaultProjectionRepositoryProviderImpl;
    provides st.orm.core.spi.QueryBuilderProvider with st.orm.core.spi.DefaultQueryBuilderProviderImpl;
    provides st.orm.core.spi.SqlDialectProvider with st.orm.core.spi.DefaultSqlDialectProviderImpl;
    provides st.orm.core.spi.ConnectionProvider with st.orm.core.spi.JdbcConnectionProviderImpl;
    provides st.orm.core.spi.TransactionTemplateProvider with st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
}
