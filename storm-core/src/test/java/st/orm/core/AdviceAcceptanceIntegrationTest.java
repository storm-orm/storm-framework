/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.AcceptAdvice;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;
import st.orm.Pageable;
import st.orm.core.model.DesignModel.Chain0;
import st.orm.core.model.Owner;
import st.orm.core.repository.EntityRepository;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.AdviceLog;
import st.orm.core.template.impl.AdviceLogTestSupport;

/**
 * {@link AcceptAdvice} on the entity a statement reads, or on a method, class or repository on its call path, keeps
 * the advice it names out of the advice log; other advice, and the same advice elsewhere, is still reported. Each
 * statement below reads a page of its own size, so each has a shape of its own.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class AdviceAcceptanceIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    public void reset() {
        AdviceLogTestSupport.reset();
    }

    @AcceptAdvice("unordered-limit")
    @DbTable("city")
    public record AcceptedCity(@PK Integer id, String name) implements Entity<Integer> {}

    @AcceptAdvice("entity-budget")
    public record AcceptedRoot(@PK Integer id, @FK Chain0 next) implements Entity<Integer> {}

    @AcceptAdvice
    public interface AcceptingOwners extends EntityRepository<Owner, Integer> {}

    public interface Owners extends EntityRepository<Owner, Integer> {

        @AcceptAdvice("unordered-limit")
        default List<Owner> acceptedPage() {
            return select().limit(3).getResultList();
        }

        default List<Owner> page() {
            return select().limit(4).getResultList();
        }
    }

    @AcceptAdvice({"deep-offset", "unordered-limit"})
    static final class AcceptingService {

        static void read(ORMTemplate orm) {
            orm.entity(Owner.class).select().limit(5).getResultList();
        }
    }

    /** Collects the advice lines reported at {@code INFO} while the action runs. */
    private static List<String> advice(Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.advice");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(null);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ORMTemplate orm() {
        return ORMTemplate.of(dataSource);
    }

    private void unorderedPage(int size) {
        orm().entity(Owner.class).select().limit(size).getResultList();
    }

    @AcceptAdvice("unordered-limit")
    private void acceptedUnorderedPage(int size) {
        unorderedPage(size);
    }

    @AcceptAdvice("page-count")
    private void otherAdviceAccepted(int size) {
        unorderedPage(size);
    }

    @Test
    public void adviceAcceptedOnTheCallingMethodIsNotReported() {
        assertEquals(List.of(), advice(() -> acceptedUnorderedPage(6)));
        // The advice is still reported where it is not accepted.
        assertEquals(1, advice(() -> unorderedPage(7)).size());
    }

    @Test
    public void adviceAcceptedInALambdaOfTheCallingMethodIsNotReported() {
        assertEquals(List.of(), advice(this::acceptedInLambda));
    }

    @AcceptAdvice("unordered-limit")
    private void acceptedInLambda() {
        Runnable read = () -> unorderedPage(8);
        read.run();
    }

    @Test
    public void adviceAcceptedOnTheCallingClassIsNotReported() {
        assertEquals(List.of(), advice(() -> AcceptingService.read(orm())));
    }

    @Test
    public void adviceTheAnnotationDoesNotNameIsReported() {
        assertEquals(1, advice(() -> otherAdviceAccepted(9)).size());
    }

    @Test
    public void adviceAcceptedOnTheEntityIsNotReported() {
        assertEquals(List.of(), advice(() -> orm().entity(AcceptedCity.class).select().limit(10).getResultList()));
    }

    @Test
    public void adviceAcceptedOnARepositoryIsNotReported() {
        // A page without a sort order takes part of the result without an ORDER BY.
        assertEquals(List.of(), advice(() -> orm().repository(AcceptingOwners.class).page(Pageable.ofSize(11))));
        assertEquals(1, advice(() -> orm().repository(Owners.class).page(Pageable.ofSize(12))).size());
    }

    @Test
    public void adviceAcceptedOnARepositoryMethodIsNotReported() {
        var owners = orm().repository(Owners.class);
        assertEquals(List.of(), advice(owners::acceptedPage));
        assertEquals(1, advice(owners::page).size());
    }

    @Test
    public void designAdviceAcceptedOnTheEntityIsNotReported() {
        AdviceLog.development();
        assertEquals(List.of(), advice(() -> AdviceLogTestSupport.checkDesign(List.of(AcceptedRoot.class))));
        assertEquals(1, advice(() -> AdviceLogTestSupport.checkDesign(List.of(Chain0.class))).size());
    }
}
