package st.orm.spring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.core.DecoratingProxy;
import st.orm.PersistenceException;
import st.orm.repository.Repository;
import st.orm.spring.impl.RepositoryAopAutoConfiguration;
import st.orm.spring.impl.RepositoryProxyingPostProcessor;

/**
 * Unit tests for coverage of implementation classes in st.orm.spring.impl.
 */
public class ImplTest {

    // RepositoryAopAutoConfiguration (2 lines)

    @Test
    public void aopAutoConfigurationShouldProvideProxyPostProcessor() {
        // RepositoryAopAutoConfiguration provides a RepositoryProxyingPostProcessor bean
        // that wraps repository beans in Spring AOP proxies.
        var config = new RepositoryAopAutoConfiguration();
        assertNotNull(config);

        var postProcessor = RepositoryAopAutoConfiguration.stormRepositoryProxyingPostProcessor();
        assertNotNull(postProcessor);
        assertInstanceOf(RepositoryProxyingPostProcessor.class, postProcessor);
    }

    // RepositoryProxyingPostProcessor (6 lines)

    @Test
    public void repositoryProxyingPostProcessorShouldWrapRepositoryBeans() {
        // RepositoryProxyingPostProcessor wraps beans that implement Repository in a proxy
        // that adds SQL logging capabilities. Repository beans should be wrapped.
        var postProcessor = new RepositoryProxyingPostProcessor();
        var repo = (Repository) Proxy.newProxyInstance(
                Repository.class.getClassLoader(),
                new Class<?>[]{ Repository.class },
                (proxy, method, args) -> null
        );
        Object result = postProcessor.postProcessAfterInitialization(repo, "testRepo");
        assertNotNull(result);
        assertInstanceOf(Repository.class, result);
    }

    interface CityRepository extends Repository {}

    static final class GeneratedCityRepository implements CityRepository {
        @Override
        public st.orm.template.ORMTemplate orm() {
            return null;
        }
    }

    @Test
    public void repositoryProxyingPostProcessorShouldProxyGeneratedClassByInterface() {
        // A repository class Storm generates is wrapped in a JDK proxy of its interface, never a CGLIB subclass,
        // which a native image cannot write at runtime.
        var postProcessor = new RepositoryProxyingPostProcessor();
        Object result = postProcessor.postProcessAfterInitialization(new GeneratedCityRepository(), "cities");
        assertTrue(Proxy.isProxyClass(result.getClass()));
        assertArrayEquals(new Class<?>[]{ CityRepository.class, SpringProxy.class, Advised.class, DecoratingProxy.class },
                result.getClass().getInterfaces());
    }

    @Test
    public void repositoryProxyingPostProcessorShouldProxyJdkProxyInTheSameShape() {
        // A repository Storm serves with a JDK proxy gets the same Spring proxy shape as a generated class, so one
        // native proxy hint covers both.
        var postProcessor = new RepositoryProxyingPostProcessor();
        var cities = (CityRepository) Proxy.newProxyInstance(
                CityRepository.class.getClassLoader(),
                new Class<?>[]{ CityRepository.class },
                (proxy, method, args) -> null
        );
        Object result = postProcessor.postProcessAfterInitialization(cities, "cities");
        assertArrayEquals(new Class<?>[]{ CityRepository.class, SpringProxy.class, Advised.class, DecoratingProxy.class },
                result.getClass().getInterfaces());
    }

    @Test
    public void repositoryProxyingPostProcessorShouldNotWrapNonRepositoryBeans() {
        // Non-Repository beans should pass through the post-processor unchanged.
        var postProcessor = new RepositoryProxyingPostProcessor();
        var bean = "not a repository";
        Object result = postProcessor.postProcessAfterInitialization(bean, "testBean");
        assertSame(bean, result);
    }

    // SpringConnectionProvider error path (2 lines)

    @Test
    public void connectionProviderShouldWrapSqlExceptionInPersistenceException() {
        // When the underlying DataSource throws a SQLException, the SpringConnectionProvider
        // should wrap it in a PersistenceException to maintain the framework's exception hierarchy.
        var provider = new SpringConnectionProvider();
        var dataSource = mock(DataSource.class);
        try {
            when(dataSource.getConnection()).thenThrow(new SQLException("Connection failed"));
        } catch (SQLException e) {
            fail("Unexpected exception during mock setup");
        }
        assertThrows(PersistenceException.class, () -> provider.getConnection(dataSource, null));
    }

    // RepositoryAutowireCandidateResolver delegation methods (covers hasQualifier, getLazyResolutionProxyClass, cloneIfNecessary, isAutowireCandidate with qualifier)

    @Test
    public void autowireCandidateResolverShouldDelegateHasQualifier() {
        // RepositoryAutowireCandidateResolver is a decorator that delegates most calls to the
        // original resolver. hasQualifier should pass through to the delegate.
        var delegate = mock(AutowireCandidateResolver.class);
        var descriptor = mock(DependencyDescriptor.class);
        when(delegate.hasQualifier(descriptor)).thenReturn(true);

        var resolver = new RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate);
        assertTrue(resolver.hasQualifier(descriptor));
        verify(delegate).hasQualifier(descriptor);
    }

    @Test
    public void autowireCandidateResolverShouldDelegateGetLazyResolutionProxyClass() {
        // getLazyResolutionProxyClass should pass through to the delegate unchanged.
        var delegate = mock(AutowireCandidateResolver.class);
        var descriptor = mock(DependencyDescriptor.class);
        doReturn(String.class).when(delegate).getLazyResolutionProxyClass(descriptor, "bean");

        var resolver = new RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate);
        assertEquals(String.class, resolver.getLazyResolutionProxyClass(descriptor, "bean"));
        verify(delegate).getLazyResolutionProxyClass(descriptor, "bean");
    }

    @Test
    public void autowireCandidateResolverShouldDelegateCloneIfNecessary() {
        // cloneIfNecessary should pass through to the delegate unchanged.
        var delegate = mock(AutowireCandidateResolver.class);
        var cloned = mock(AutowireCandidateResolver.class);
        when(delegate.cloneIfNecessary()).thenReturn(cloned);

        var resolver = new RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate);
        assertSame(cloned, resolver.cloneIfNecessary());
        verify(delegate).cloneIfNecessary();
    }

    @Test
    public void autowireCandidateResolverShouldMatchWhenQualifierValuesAreEqual() {
        // When the delegate says "no" but the bean definition has a qualifier attribute matching
        // the @Qualifier annotation on the injection point, the resolver should return true.
        // This enables repository beans registered by the post-processor to be autowired by qualifier.
        var delegate = mock(AutowireCandidateResolver.class);
        var descriptor = mock(DependencyDescriptor.class);

        var beanDef = new GenericBeanDefinition();
        beanDef.setAttribute("qualifier", "myPrefix");
        var holder = new BeanDefinitionHolder(beanDef, "testBean");

        when(delegate.isAutowireCandidate(holder, descriptor)).thenReturn(false);

        Qualifier qualifierAnnotation = new Qualifier() {
            @Override public String value() { return "myPrefix"; }
            @Override public Class<? extends Annotation> annotationType() { return Qualifier.class; }
        };
        when(descriptor.getAnnotations()).thenReturn(new Annotation[]{ qualifierAnnotation });

        var resolver = new RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate);
        assertTrue(resolver.isAutowireCandidate(holder, descriptor));
    }

    @Test
    public void autowireCandidateResolverShouldRejectWhenQualifierValuesDiffer() {
        // When qualifier values differ between the bean definition and the injection point,
        // the resolver should return false (the bean is not a candidate for this injection point).
        var delegate = mock(AutowireCandidateResolver.class);
        var descriptor = mock(DependencyDescriptor.class);

        var beanDef = new GenericBeanDefinition();
        beanDef.setAttribute("qualifier", "otherPrefix");
        var holder = new BeanDefinitionHolder(beanDef, "testBean");

        when(delegate.isAutowireCandidate(holder, descriptor)).thenReturn(false);

        Qualifier qualifierAnnotation = new Qualifier() {
            @Override public String value() { return "myPrefix"; }
            @Override public Class<? extends Annotation> annotationType() { return Qualifier.class; }
        };
        when(descriptor.getAnnotations()).thenReturn(new Annotation[]{ qualifierAnnotation });

        var resolver = new RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate);
        assertFalse(resolver.isAutowireCandidate(holder, descriptor));
    }

    @Test
    public void autowireCandidateResolverShouldRejectWhenNoQualifierPresent() {
        // When the delegate says "no" and the injection point has no @Qualifier annotation,
        // the resolver should return false (no qualifier fallback to check).
        var delegate = mock(AutowireCandidateResolver.class);
        var descriptor = mock(DependencyDescriptor.class);

        var beanDef = new GenericBeanDefinition();
        var holder = new BeanDefinitionHolder(beanDef, "testBean");

        when(delegate.isAutowireCandidate(holder, descriptor)).thenReturn(false);
        when(descriptor.getAnnotations()).thenReturn(new Annotation[0]);

        var resolver = new RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate);
        assertFalse(resolver.isAutowireCandidate(holder, descriptor));
    }
}
