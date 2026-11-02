package st.orm.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Data;
import st.orm.JoinType;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.TypedMetamodel;
import st.orm.Window;
import st.orm.template.model.City;

/**
 * The result terminals a query builder inherits stream the results, so a minimal subclass exercises them directly.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@SpringBootTest
@Sql("/data.sql")
public class BaseQueryBuilderTest {

    @Autowired
    private DataSource dataSource;

    private static final class ForwardingQueryBuilder<T extends Data, R, ID> extends QueryBuilder<T, R, ID> {

        private final QueryBuilder<T, R, ID> delegate;

        ForwardingQueryBuilder(QueryBuilder<T, R, ID> delegate) {
            this.delegate = delegate;
        }

        @Override
        public <X> QueryBuilder<T, R, X> typedId(Class<X> pkType) {
            return delegate.typedId(pkType);
        }

        @Override
        public <X extends Data> QueryBuilder<X, R, ID> narrow(Class<X> rootType) {
            return delegate.narrow(rootType);
        }

        @Override
        public QueryBuilder<Data, R, ID> widen() {
            return delegate.widen();
        }

        @Override
        public QueryBuilder<T, R, ID> unsafe() {
            return delegate.unsafe();
        }

        @Override
        public <V> QueryBuilder<T, R, ID> set(Metamodel<? extends T, V> field, @Nullable V value) {
            return delegate.set(field, value);
        }

        @Override
        public <V extends Data> QueryBuilder<T, R, ID> set(Metamodel<? extends T, V> field, Ref<V> ref) {
            return delegate.set(field, ref);
        }

        @Override
        public QueryBuilder<T, R, ID> set(Metamodel<? extends T, ?> field, StringTemplate template) {
            return delegate.set(field, template);
        }

        @Override
        public QueryBuilder<T, R, ID> distinct() {
            return delegate.distinct();
        }

        @Override
        public QueryBuilder<T, R, ID> fetch(List<? extends Navigable<T, ? extends Data>> paths) {
            return delegate.fetch(paths);
        }

        @Override
        public QueryBuilder<Data, R, ID> crossJoin(Class<? extends Data> relation) {
            return delegate.crossJoin(relation);
        }

        @Override
        public TypedJoinBuilder<T, R, ID> innerJoin(Class<? extends Data> relation) {
            return delegate.innerJoin(relation);
        }

        @Override
        public TypedJoinBuilder<T, R, ID> leftJoin(Class<? extends Data> relation) {
            return delegate.leftJoin(relation);
        }

        @Override
        public TypedJoinBuilder<T, R, ID> rightJoin(Class<? extends Data> relation) {
            return delegate.rightJoin(relation);
        }

        @Override
        public TypedJoinBuilder<T, R, ID> semiJoin(Class<? extends Data> relation) {
            return delegate.semiJoin(relation);
        }

        @Override
        public TypedJoinBuilder<T, R, ID> antiJoin(Class<? extends Data> relation) {
            return delegate.antiJoin(relation);
        }

        @Override
        public TypedJoinBuilder<T, R, ID> join(JoinType type, Class<? extends Data> relation, String alias) {
            return delegate.join(type, relation, alias);
        }

        @Override
        public QueryBuilder<Data, R, ID> crossJoin(StringTemplate template) {
            return delegate.crossJoin(template);
        }

        @Override
        public JoinBuilder<T, R, ID> innerJoin(StringTemplate template, String alias) {
            return delegate.innerJoin(template, alias);
        }

        @Override
        public JoinBuilder<T, R, ID> leftJoin(StringTemplate template, String alias) {
            return delegate.leftJoin(template, alias);
        }

        @Override
        public JoinBuilder<T, R, ID> rightJoin(StringTemplate template, String alias) {
            return delegate.rightJoin(template, alias);
        }

        @Override
        public JoinBuilder<T, R, ID> join(JoinType type, StringTemplate template, String alias) {
            return delegate.join(type, template, alias);
        }

        @Override
        public JoinBuilder<T, R, ID> join(JoinType type, QueryBuilder<?, ?, ?> subquery, String alias) {
            return delegate.join(type, subquery, alias);
        }

        @Override
        public QueryBuilder<T, R, ID> where(Function<WhereBuilder<T, R, ID>, PredicateBuilder<T, ?, ?>> predicate) {
            return delegate.where(predicate);
        }

        @Override
        public QueryBuilder<T, R, ID> groupBy(StringTemplate template) {
            return delegate.groupBy(template);
        }

        @Override
        public QueryBuilder<T, R, ID> having(StringTemplate template) {
            return delegate.having(template);
        }

        @Override
        public QueryBuilder<T, R, ID> havingExists(QueryBuilder<?, ?, ?> subquery) {
            return delegate.havingExists(subquery);
        }

        @Override
        public QueryBuilder<T, R, ID> havingNotExists(QueryBuilder<?, ?, ?> subquery) {
            return delegate.havingNotExists(subquery);
        }

        @Override
        public QueryBuilder<T, R, ID> orderBy(StringTemplate template) {
            return delegate.orderBy(template);
        }

        @Override
        public QueryBuilder<T, R, ID> orderFirst(Function<WhereBuilder<T, R, ID>, PredicateBuilder<T, ?, ?>> predicate) {
            return delegate.orderFirst(predicate);
        }

        @Override
        public QueryBuilder<T, R, ID> limit(int limit) {
            return delegate.limit(limit);
        }

        @Override
        public QueryBuilder<T, R, ID> offset(int offset) {
            return delegate.offset(offset);
        }

        @Override
        public QueryBuilder<T, R, ID> forShare() {
            return delegate.forShare();
        }

        @Override
        public QueryBuilder<T, R, ID> forUpdate() {
            return delegate.forUpdate();
        }

        @Override
        public QueryBuilder<T, R, ID> forLock(StringTemplate template) {
            return delegate.forLock(template);
        }

        @Override
        public QueryBuilder<T, R, ID> hint(String hint) {
            return delegate.hint(hint);
        }

        @Override
        public Query build() {
            return delegate.build();
        }

        @Override
        public Window<R> scroll(Scrollable<T> scrollable) {
            return delegate.scroll(scrollable);
        }

        @Override
        public Stream<Window<R>> windows(int size) {
            return delegate.windows(size);
        }

        @Override
        public Stream<Window<R>> windows(Scrollable<T> scrollable) {
            return delegate.windows(scrollable);
        }

        @Override
        public Stream<R> getResultStream() {
            return delegate.getResultStream();
        }

        @Override
        public List<R> getResultList() {
            return delegate.getResultList();
        }

        @Override
        public <V extends Data> SequencedMap<V, List<R>> getResultGroupedBy(TypedMetamodel<T, V, V> path) {
            return delegate.getResultGroupedBy(path);
        }

        @Override
        public <V extends Data> SequencedMap<Ref<V>, List<R>> getResultGroupedByRef(Metamodel<T, V> path) {
            return delegate.getResultGroupedByRef(path);
        }

        @Override
        public R getSingleResult() {
            return delegate.getSingleResult();
        }

        @Override
        public Optional<R> getOptionalResult() {
            return delegate.getOptionalResult();
        }

        @Override
        protected boolean hasOrderBy() {
            return delegate.hasOrderBy();
        }
    }

    @Test
    public void baseResultTerminalsStreamCountAndTestForARow() {
        var cities = ORMTemplate.of(dataSource).entity(City.class);
        var all = new ForwardingQueryBuilder<>(cities.select());
        assertEquals(6, all.getResultCount());
        assertEquals(6, all.getResultList().size());
        assertTrue(all.exists());
        assertThrows(NonUniqueResultException.class, all::getSingleResult);
        var none = new ForwardingQueryBuilder<>(cities.select().where(999));
        assertFalse(none.exists());
        assertThrows(NoResultException.class, none::getSingleResult);
        assertTrue(none.getOptionalResult().isEmpty());
    }
}
