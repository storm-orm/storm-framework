package st.orm;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.jspecify.annotations.Nullable;

class MetamodelHelper {

    private static final Method ROOT_METHOD;
    private static final Method OF_METHOD;
    private static final Method FLATTEN_METHOD;
    private static final Method IS_NULLABLE_METHOD;

    static {
        try {
            Class<?> factoryClass = Class.forName("st.orm.core.template.impl.MetamodelFactory");
            ROOT_METHOD = factoryClass.getMethod("root", Class.class);
            OF_METHOD = factoryClass.getMethod("of", Class.class, String.class);
            FLATTEN_METHOD = factoryClass.getMethod("flatten", Navigable.class);
            IS_NULLABLE_METHOD = factoryClass.getMethod("isNullable", Metamodel.class);
        } catch (ReflectiveOperationException e) {
            var ex = new ExceptionInInitializerError("Failed to initialize Metamodel. Please ensure that storm-core is present in the classpath.");
            ex.initCause(e);
            throw ex;
        }
    }

    private MetamodelHelper() {
        // Prevent instantiation
    }

    @SuppressWarnings("unchecked")
    static <T extends Data> Metamodel<T, T> root(Class<T> rootTable) {
        try {
            try {
                return (Metamodel<T, T>) ROOT_METHOD.invoke(null, rootTable);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Reflection invocation failed for MetamodelFactory.root", e);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException(t);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Data, E extends @Nullable Object> Metamodel<T, E> of(Class<T> rootTable, String path) {
        try {
            try {
                return (Metamodel<T, E>) OF_METHOD.invoke(null, rootTable, path);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Reflection invocation failed for MetamodelFactory.of", e);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException(t);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Data> List<Metamodel<T, ?>> flatten(Navigable<T, ?> metamodel) {
        try {
            try {
                return (List<Metamodel<T, ?>>) FLATTEN_METHOD.invoke(null, metamodel);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Reflection invocation failed for MetamodelFactory.flatten", e);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException(t);
        }
    }

    static boolean isNullable(Metamodel<?, ?> metamodel) {
        try {
            try {
                return (Boolean) IS_NULLABLE_METHOD.invoke(null, metamodel);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Reflection invocation failed for MetamodelFactory.isNullable", e);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException(t);
        }
    }
}
