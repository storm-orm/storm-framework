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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every piece of advice links to a section of the advice page, so the page keeps an anchor for each, and documents no
 * advice Storm does not give.
 */
public class AdvicePageTest {

    private static final Path PAGE = Path.of("..", "docs", "advice.md");

    private static final Pattern ANCHOR = Pattern.compile("^## .+ \\{#([a-z0-9-]+)}$", Pattern.MULTILINE);

    @Test
    public void advicePageHasASectionForEveryPieceOfAdvice() throws IOException {
        var anchors = new LinkedHashSet<String>();
        var matcher = ANCHOR.matcher(Files.readString(PAGE));
        while (matcher.find()) {
            anchors.add(matcher.group(1));
        }
        var advice = new LinkedHashSet<String>();
        Arrays.stream(Advice.values()).map(Advice::anchor).forEach(advice::add);
        assertEquals(advice, anchors, "the sections of " + PAGE + " and the advice in " + Advice.class.getSimpleName());
    }

    @Test
    public void adviceLinksToItsSection() {
        for (var advice : Advice.values()) {
            assertTrue(advice.format(3, "Owner", 3).endsWith(" See https://orm.st/docs/advice#" + advice.anchor()), advice.name());
        }
    }

    @Test
    public void anAdvisoryIsRecognizedByItsLink() {
        for (var advice : Advice.values()) {
            assertEquals(advice, Advice.of(advice.format(3, "Owner", 3)), advice.name());
        }
    }

    @Test
    public void eachPieceOfAdviceHasALoggerNamedAfterItsAnchor() {
        for (var advice : Advice.values()) {
            assertEquals("st.orm.sql.advice." + advice.anchor(), AdviceLog.logger(advice), advice.name());
        }
    }
}
