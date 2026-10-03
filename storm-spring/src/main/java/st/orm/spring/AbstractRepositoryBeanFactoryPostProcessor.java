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
package st.orm.spring;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Scanning and registration engine for Storm repository interfaces: scans base packages for interfaces
 * assignable to the repository marker type and registers them as beans, with qualifier-aware autowiring.
 *
 * <p>This engine is language-neutral. The Java and Kotlin Spring integrations each provide a thin adapter
 * binding their stack's repository marker type and repository creation call:
 * {@code st.orm.spring.RepositoryBeanFactoryPostProcessor} (Java) and
 * {@code st.orm.spring.kotlin.RepositoryBeanFactoryPostProcessor} (Kotlin).</p>
 *
 * @since 1.13
 */
public abstract class AbstractRepositoryBeanFactoryPostProcessor
        implements BeanFactoryPostProcessor, ResourceLoaderAware {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.spring.repository");

    private final Environment environment = new StandardEnvironment();
    private ResourceLoader resourceLoader;

    /** Override to point to a specific ORMTemplate bean. Null = primary/default. */
    public @Nullable String getOrmTemplateBeanName() {
        return null;
    }

    /** Override with base packages to scan. Empty = no scan. */
    public String[] getRepositoryBasePackages() {
        return new String[0];
    }

    /** Optional qualifier prefix for registered repositories. */
    protected String getRepositoryPrefix() {
        return "";
    }

    /**
     * The repository marker interface of the stack; scanned interfaces must be assignable to it.
     */
    protected abstract Class<?> getRepositoryType();

    /**
     * The framework's own repository interfaces, excluded from registration.
     */
    protected abstract Set<Class<?>> getExcludedRepositoryTypes();

    /**
     * The stack's repository FactoryBean; one bean definition of this type is registered per discovered
     * repository interface, honoring {@link #getOrmTemplateBeanName()}.
     *
     * @since 1.13
     */
    protected abstract Class<? extends AbstractRepositoryFactoryBean<?>> getRepositoryFactoryBeanClass();

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (AotDetector.useGeneratedArtifacts()) {
            // The repository bean definitions registered by this post-processor were turned into generated
            // code during AOT processing; scanning again would re-register them over the generated ones.
            return;
        }
        String[] bases = getRepositoryBasePackages();
        if (bases == null || bases.length == 0) {
            return;
        }
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, environment) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        var md = beanDefinition.getMetadata();
                        return md.isIndependent() && md.isInterface();
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(getRepositoryType()));
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
            // Read candidate metadata straight from the classloader instead of through the
            // ApplicationContext's ResourceLoader. Type-filter matching resolves each candidate's
            // supertypes by name via getResource("classpath:...class"), which is dispatched to every
            // ProtocolResolver registered on the context. A resolver bound to its own scheme (such as
            // Spring Cloud AWS's s3:// resolver) is thus consulted for these classpath lookups and logs
            // a warning per resource while its backing client bean is not yet available during scanning.
            scanner.setMetadataReaderFactory(new CachingMetadataReaderFactory(defaultClassLoader()));
        }
        var excluded = getExcludedRepositoryTypes();
        List<Class<?>> repositoryTypes = Arrays.stream(bases)
                .flatMap(base -> scanner.findCandidateComponents(base).stream())
                .map(definition -> {
                    String name = definition.getBeanClassName();
                    if (name == null) {
                        return null;
                    }
                    try {
                        return (Class<?>) ClassUtils.forName(name, defaultClassLoader());
                    } catch (Throwable ex) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(type -> !excluded.contains(type))
                .filter(type -> !hasNoRepositoryBeanAnnotation(type))
                .distinct()
                .collect(Collectors.toList());
        registerRepositories(registry, repositoryTypes.stream());
        RepositoryAutowireCandidateResolver.register(beanFactory);
    }

    private void registerRepositories(
            BeanDefinitionRegistry registry,
            Stream<Class<?>> repositories
    ) {
        repositories.forEach(type -> {
            var factoryBeanClass = getRepositoryFactoryBeanClass();
            String prefix = getRepositoryPrefix();
            // A FactoryBean definition carrying the repository interface as a constructor argument, rather
            // than an instance supplier: suppliers cannot be processed by Spring AOT into generated code,
            // which would make scanned repositories unavailable in GraalVM native images.
            var definition = (RootBeanDefinition) BeanDefinitionBuilder
                    .rootBeanDefinition(factoryBeanClass)
                    .addConstructorArgValue(type)
                    .addConstructorArgValue(getOrmTemplateBeanName())
                    .getBeanDefinition();
            // Expose the produced type without instantiating the FactoryBean.
            definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, type);
            // The attribute is not carried into AOT-generated registrations; the target type is, and it
            // binds the FactoryBean's generic to the repository interface so the repository can still be
            // autowired by type in a native image.
            definition.setTargetType(ResolvableType.forClassWithGenerics(factoryBeanClass, type));
            definition.setAttribute("qualifier", prefix);
            if (prefix != null && !prefix.isEmpty()) {
                // The qualifier attribute supports autowiring by qualifier on the JVM; the definition-level
                // qualifier survives AOT processing, where attributes are not carried into generated code.
                definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, prefix));
                LOGGER.debug("Registering repository {} with qualifier {}.", type.getName(), prefix);
            } else {
                LOGGER.debug("Registering repository {}.", type.getName());
            }
            String name = prefix + type.getSimpleName();
            registry.registerBeanDefinition(name, definition);
        });
    }

    /**
     * Reflectively check for @NoRepositoryBean.
     */
    @SuppressWarnings("unchecked")
    private boolean hasNoRepositoryBeanAnnotation(Class<?> type) {
        String fullyQualifiedName = "org.springframework.data.repository.NoRepositoryBean";
        Class<?> loaded;
        try {
            loaded = Class.forName(fullyQualifiedName, false, type.getClassLoader());
        } catch (ClassNotFoundException e) {
            return false;
        }
        if (Annotation.class.isAssignableFrom(loaded)) {
            var annotationType = (Class<? extends Annotation>) loaded;
            return type.isAnnotationPresent(annotationType);
        }
        return false;
    }

    private ClassLoader defaultClassLoader() {
        if (resourceLoader != null && resourceLoader.getClassLoader() != null) {
            return resourceLoader.getClassLoader();
        }
        ClassLoader cl = ClassUtils.getDefaultClassLoader();
        return (cl != null ? cl : ClassLoader.getSystemClassLoader());
    }

    /**
     * Qualifier-aware AutowireCandidateResolver that reads the "qualifier" attribute set on the repository
     * bean definitions registered by this post-processor.
     */
    public static class RepositoryAutowireCandidateResolver implements AutowireCandidateResolver {
        private final AutowireCandidateResolver delegate;

        public RepositoryAutowireCandidateResolver(AutowireCandidateResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isRequired(DependencyDescriptor descriptor) {
            return delegate.isRequired(descriptor);
        }

        @Override
        public boolean hasQualifier(DependencyDescriptor descriptor) {
            return delegate.hasQualifier(descriptor);
        }

        @Override
        public Object getSuggestedValue(DependencyDescriptor descriptor) {
            return delegate.getSuggestedValue(descriptor);
        }

        @Override
        public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, String beanName) {
            return delegate.getLazyResolutionProxyIfNecessary(descriptor, beanName);
        }

        @Override
        public Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, String beanName) {
            return delegate.getLazyResolutionProxyClass(descriptor, beanName);
        }

        @Override
        public AutowireCandidateResolver cloneIfNecessary() {
            return delegate.cloneIfNecessary();
        }

        @Override
        public boolean isAutowireCandidate(BeanDefinitionHolder holder, DependencyDescriptor descriptor) {
            if (delegate.isAutowireCandidate(holder, descriptor)) {
                return true;
            }
            String requiredQualifier = getRequiredQualifier(descriptor);
            if (requiredQualifier != null) {
                Object attr = holder.getBeanDefinition().getAttribute("qualifier");
                return requiredQualifier.equals(attr);
            }
            return false;
        }

        private String getRequiredQualifier(DependencyDescriptor descriptor) {
            for (Annotation annotation : descriptor.getAnnotations()) {
                String value = getQualifierValue(annotation);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private String getQualifierValue(Annotation annotation) {
            if (annotation instanceof Qualifier qualifier) {
                return qualifier.value();
            }
            Qualifier metadata = annotation.annotationType().getAnnotation(Qualifier.class);
            return metadata != null ? metadata.value() : null;
        }

        public static void register(ConfigurableListableBeanFactory beanFactory) {
            DefaultListableBeanFactory dlbf = (DefaultListableBeanFactory) beanFactory;
            dlbf.setAutowireCandidateResolver(
                    new RepositoryAutowireCandidateResolver(dlbf.getAutowireCandidateResolver()));
        }
    }
}
