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
package st.orm.spring.impl;

import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
public class RepositoryProxyingPostProcessor implements BeanPostProcessor {

    private final @Nullable Class<?> repositoryType;

    /**
     * Resolves the repository marker type reflectively: {@code st.orm.repository.Repository} exists in both
     * the Java and the Kotlin stack (same fully qualified name, one per classpath). When neither stack is
     * present, the post-processor is a no-op.
     */
    public RepositoryProxyingPostProcessor() {
        this(resolveDefaultRepositoryType());
    }

    public RepositoryProxyingPostProcessor(@Nullable Class<?> repositoryType) {
        this.repositoryType = repositoryType;
    }

    private static @Nullable Class<?> resolveDefaultRepositoryType() {
        try {
            return ClassUtils.forName("st.orm.repository.Repository", RepositoryProxyingPostProcessor.class.getClassLoader());
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (repositoryType != null && repositoryType.isInstance(bean)) {
            // The repository interfaces the bean's class implements directly, which leaves out Serializable for a JDK
            // proxy target: the Spring proxy is a JDK proxy of the same interfaces whether Storm served the repository
            // with a JDK proxy or a generated class. A class-based proxy would need a CGLIB subclass written at
            // runtime, which a native image cannot do.
            ProxyFactory factory = new ProxyFactory();
            factory.setTarget(bean);
            factory.setInterfaces(bean.getClass().getInterfaces());
            return factory.getProxy();
        }
        return bean;
    }
}
