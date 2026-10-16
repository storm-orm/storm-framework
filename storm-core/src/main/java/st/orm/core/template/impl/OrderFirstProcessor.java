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

import st.orm.core.template.impl.Elements.OrderFirst;

/**
 * A processor for the mark of an ORDER BY key that ranks the rows by a condition: it renders nothing.
 *
 * @since 1.15
 */
final class OrderFirstProcessor implements ElementProcessor<OrderFirst> {

    @Override
    public Object getCompilationKey(OrderFirst orderFirst) {
        return orderFirst;
    }

    @Override
    public CompiledElement compile(OrderFirst orderFirst, TemplateCompiler compiler) {
        return new CompiledElement("");
    }

    @Override
    public void bind(OrderFirst orderFirst, TemplateBinder binder, BindHint bindHint) {
    }
}
