module storm.java {
    exports st.orm.repository;
    exports st.orm.template;
    requires java.sql;
    requires static jakarta.persistence;
    requires jakarta.annotation;
    requires java.compiler;
    requires storm.foundation;
    requires storm.core;
}
