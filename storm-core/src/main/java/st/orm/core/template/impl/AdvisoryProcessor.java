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
package st.orm.core.template.impl;

import st.orm.core.template.impl.Elements.Advisory;

/**
 * A processor for an advisory element of a template: it renders nothing and records the advice on the statement.
 *
 * @since 1.16
 */
final class AdvisoryProcessor implements ElementProcessor<Advisory> {

    @Override
    public Object getCompilationKey(Advisory advisory) {
        return advisory;
    }

    @Override
    public CompiledElement compile(Advisory advisory, TemplateCompiler compiler) {
        compiler.addAdvisory(advisory.text());
        return new CompiledElement("");
    }

    @Override
    public void bind(Advisory advisory, TemplateBinder binder, BindHint bindHint) {
    }
}
