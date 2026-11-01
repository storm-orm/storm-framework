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
package st.orm.spring.boot;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import st.orm.core.template.SqlLog;

/**
 * Wraps the entry points a request filter cannot see in a SQL log: methods through which work enters the
 * application without an HTTP request, such as {@code @Scheduled} tasks and {@code @KafkaListener},
 * {@code @RabbitListener}, {@code @JmsListener} or {@code @SqsListener} handlers, including the
 * {@code @KafkaHandler} and {@code @RabbitHandler} methods of a class-level listener.
 *
 * <p>Each invocation reports as one summary named after the method ({@code ReportJob.nightly}), with the same
 * thresholds and through the same {@code st.orm.sql.perf} logger as the per-request filter, so what a scheduled
 * import or a queue consumer cost the database reads exactly like what a request cost it.</p>
 *
 * <p>Entry points are matched by annotation type name, directly present on the bean method, so a default whose
 * library is absent from the classpath simply never matches and costs nothing. The set is configurable through
 * {@code storm.sql-log.performance.entry-points}.</p>
 *
 * <p>The scope follows the invoking thread, which is where a blocking task or listener does its work. Work the
 * method hands to another thread falls outside it.</p>
 *
 * <p>The proxy is a subclass of the bean's class. Its class is also built when Spring determines the bean types
 * during AOT processing, so the AOT build writes it out and a native image, which cannot write a class at runtime,
 * holds it.</p>
 *
 * @since 1.13
 */
public class StormPerformanceLogEntryPointPostProcessor
        implements SmartInstantiationAwareBeanPostProcessor, Ordered, PerformanceLog.Boundary {

    private static final Logger LOGGER = PerformanceLog.LOGGER;

    private final Set<String> entryPointAnnotations;

    /** Read per invocation, so a replacement takes effect on the invocation after it. */
    private volatile PerformanceLog.Settings settings;

    /**
     * Creates the post-processor.
     *
     * @param entryPointAnnotations fully qualified annotation type names that mark a method as an entry point.
     * @param limit the number of statements to record per invocation; the summary counts the rest regardless.
     * @param callSites whether to attribute each execution to the application frame that caused it.
     * @param statementThreshold number of statements above which an invocation is reported, or {@code null}.
     * @param durationThreshold invocation duration above which an invocation is reported, or {@code null}.
     */
    public StormPerformanceLogEntryPointPostProcessor(Set<String> entryPointAnnotations,
                                                int limit,
                                                boolean callSites,
                                                @Nullable Integer statementThreshold,
                                                @Nullable Duration durationThreshold) {
        this.entryPointAnnotations = Set.copyOf(entryPointAnnotations);
        this.settings = new PerformanceLog.Settings(limit, callSites, statementThreshold, durationThreshold);
    }

    @Override
    public PerformanceLog.Settings settings() {
        return settings;
    }

    @Override
    public void settings(PerformanceLog.Settings settings) {
        this.settings = settings;
    }

    @Override
    public String boundaryName() {
        return "entry-point";
    }

    /**
     * Runs before the scheduling and listener annotation post-processors, which sit at the lowest precedence, so
     * they register their invocations against the wrapped bean.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    /**
     * Returns the class of the proxy that wraps a bean of the type, building it the way
     * {@link #postProcessAfterInitialization} does. Spring calls this during AOT processing, which writes out the
     * proxy classes built in it.
     */
    @Override
    public Class<?> determineBeanType(Class<?> beanClass, String beanName) {
        if (SpringProxy.class.isAssignableFrom(beanClass) || Modifier.isFinal(beanClass.getModifiers())
                || entryPoints(beanClass).isEmpty()) {
            // A Spring proxy is advised already, so the scope joins it and no class of its own is built.
            return beanClass;
        }
        var proxyFactory = new ProxyFactory();
        proxyFactory.setTargetClass(beanClass);
        // The interfaces the proxy of the bean instance implements, as the image looks the class up by name only.
        proxyFactory.setInterfaces(ClassUtils.getAllInterfacesForClass(beanClass, beanClass.getClassLoader()));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvisor(advisor(beanClass));
        return proxyFactory.getProxyClass(beanClass.getClassLoader());
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        List<Method> entryPoints = entryPoints(targetClass);
        if (entryPoints.isEmpty()) {
            return bean;
        }
        for (var method : entryPoints) {
            if (Modifier.isFinal(method.getModifiers())) {
                LOGGER.warn("Cannot wrap {}.{} in a SQL log: the method is final, so the proxy cannot "
                        + "intercept it. Open a scope inside the method instead.",
                        targetClass.getSimpleName(), method.getName());
            }
        }
        var advisor = advisor(targetClass);
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            // The bean is proxied already; the scope joins that proxy, outermost so it covers the other advice.
            advised.addAdvisor(0, advisor);
            return bean;
        }
        if (Modifier.isFinal(targetClass.getModifiers())) {
            LOGGER.warn("Cannot wrap {} in a SQL log: the class is final, so it cannot be proxied. Open a "
                    + "scope inside the entry point instead.", targetClass.getSimpleName());
            return bean;
        }
        var proxyFactory = new ProxyFactory(bean);
        // The scheduling and listener post-processors read their annotations from the target class, so the proxy
        // has to be a subclass for the entry points to remain discoverable.
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvisor(advisor);
        return proxyFactory.getProxy(targetClass.getClassLoader());
    }

    /** Returns the entry points of the class, none when no entry point annotations are configured. */
    private List<Method> entryPoints(Class<?> targetClass) {
        List<Method> entryPoints = new ArrayList<>();
        if (!entryPointAnnotations.isEmpty()) {
            ReflectionUtils.doWithMethods(targetClass, method -> {
                if (isEntryPoint(method)) {
                    entryPoints.add(method);
                }
            });
        }
        return entryPoints;
    }

    private DefaultPointcutAdvisor advisor(Class<?> targetClass) {
        return new DefaultPointcutAdvisor(new EntryPointPointcut(), new EntryPointInterceptor(targetClass));
    }

    private boolean isEntryPoint(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (entryPointAnnotations.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    /** Routes only the entry points through the interceptor; every other method of the bean stays untouched. */
    private final class EntryPointPointcut extends StaticMethodMatcherPointcut {
        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            return isEntryPoint(AopUtils.getMostSpecificMethod(method, targetClass));
        }
    }

    /** Wraps an invocation in a scope named after the method, reporting like the per-request filter. */
    private final class EntryPointInterceptor implements MethodInterceptor {
        private final Class<?> targetClass;

        private EntryPointInterceptor(Class<?> targetClass) {
            this.targetClass = targetClass;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            // Read once, so a replacement mid-invocation cannot report an invocation against settings it was not
            // recorded under.
            var settings = StormPerformanceLogEntryPointPostProcessor.this.settings;
            if (!PerformanceLog.consumes(settings)) {
                // Nothing consumes the summary, so do not open a scope to build one.
                return invocation.proceed();
            }
            String name = targetClass.getSimpleName() + "." + invocation.getMethod().getName();
            var scope = SqlLog.open(name, settings.limit(), settings.callSites());
            try {
                return invocation.proceed();
            } finally {
                scope.close();
                PerformanceLog.report(scope.summary(), settings);
            }
        }
    }
}
