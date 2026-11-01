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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.GeneratedFiles.Kind;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * A native image cannot write a class at runtime, so the proxy that wraps an entry point in a SQL log has to be
 * written by the AOT build.
 */
public class EntryPointAotTest {

    public interface Job {
        void run();
    }

    public static class ReportJob implements Job {
        @Scheduled(fixedDelay = Long.MAX_VALUE)
        @Override
        public void run() {
        }
    }

    public static class PlainService {
        public void doWork() {
        }
    }

    private static Set<String> processAheadOfTime() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBeanDefinition("entryPoints", BeanDefinitionBuilder
                .genericBeanDefinition(StormPerformanceLogEntryPointPostProcessor.class)
                .addConstructorArgValue(Set.of(Scheduled.class.getName()))
                .addConstructorArgValue(100)
                .addConstructorArgValue(false)
                .addConstructorArgValue(null)
                .addConstructorArgValue(null)
                .getBeanDefinition());
        context.registerBeanDefinition("reportJob",
                BeanDefinitionBuilder.genericBeanDefinition(ReportJob.class).getBeanDefinition());
        context.registerBeanDefinition("plainService",
                BeanDefinitionBuilder.genericBeanDefinition(PlainService.class).getBeanDefinition());
        TestGenerationContext generationContext = new TestGenerationContext();
        new ApplicationContextAotGenerator().processAheadOfTime(context, generationContext);
        generationContext.writeGeneratedContent();
        return generationContext.getGeneratedFiles().getGeneratedFiles(Kind.CLASS).keySet();
    }

    @Test
    public void testAotProcessingWritesTheProxyOfAnEntryPointBean() {
        Set<String> classes = processAheadOfTime();
        assertTrue(classes.contains(
                        "st/orm/spring/boot/EntryPointAotTest$ReportJob$$SpringCGLIB$$0.class"),
                "The proxy of the entry point bean must be written by the AOT build, but got " + classes);
        assertFalse(classes.stream().anyMatch(name -> name.contains("PlainService$$SpringCGLIB")),
                "A bean without entry points must not get a proxy class");
    }

    @Test
    public void testTheProxyClassImplementsTheInterfacesOfTheBean() {
        var postProcessor = new StormPerformanceLogEntryPointPostProcessor(
                Set.of(Scheduled.class.getName()), 100, false, null, null);
        Class<?> proxyClass = postProcessor.determineBeanType(ReportJob.class, "reportJob");
        assertTrue(ReportJob.class.isAssignableFrom(proxyClass));
        assertTrue(Job.class.isAssignableFrom(proxyClass));
        assertTrue(proxyClass.getName().contains("$$SpringCGLIB$$"), proxyClass.getName());
    }
}
