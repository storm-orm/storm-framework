// The module name carries the Jackson major line it binds, which javac's module lint flags as a terminal digit.
@SuppressWarnings("module")
module storm.jackson2 {
    exports st.orm.jackson;
    requires storm.foundation;
    requires storm.core;
    requires static org.jspecify;
    requires java.sql;
    requires com.fasterxml.jackson.databind;
    provides st.orm.core.spi.ORMConverterProvider with st.orm.jackson.spi.JsonORMConverterProviderImpl;
}
