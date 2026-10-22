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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import org.jspecify.annotations.Nullable;

/**
 * The lengths an {@code IN} list has rendered with, per statement shape, from which a statement that has run as
 * many statements to the database carries {@link Advice#IN_LIST_SHAPES}.
 *
 * <p>A statement with a placeholder per value is one shape to Storm, whatever the length of its list, and a
 * different statement to the database for every length. The lengths are recorded where the list binds in the
 * default mode without an array, and once a shape has rendered with {@link Advice#IN_LIST_LENGTHS} distinct
 * lengths its statements carry the advice from then on. The advice names the pattern and the setting; the count of
 * lengths is a measurement, which a slow line of the shape reports as a fact, live.</p>
 *
 * <p>Recorded while the advice log reports the advice, which is development, and while the slow statement log is
 * active, so that a slow execution in production carries the advice on its line once its shape has run in enough
 * lengths; a deployment with neither pays one test per list. The lengths of a shape are kept as bits, set without
 * a lock, and lengths beyond their range share the last bit; the shapes are bounded as the advice log's own records
 * are.</p>
 *
 * @since 1.16
 */
final class InListShapes {

    private InListShapes() {
    }

    /** The longest list recorded as its own length; longer lists share one length. */
    static final int MAX_LENGTH = 4_096;

    private static final ConcurrentHashMap<Long, Lengths> SHAPES = new ConcurrentHashMap<>();

    /** The advice every shape past the threshold carries; its text names the pattern, not the count. */
    private static final String ADVISORY = Advice.IN_LIST_SHAPES.format();

    /** The lengths one shape has rendered with, as bits, recorded without a lock. */
    private static final class Lengths {
        private final AtomicLongArray words = new AtomicLongArray(MAX_LENGTH / Long.SIZE + 1);
        private final AtomicInteger distinct = new AtomicInteger();

        /** Records the length and returns the advice the shape carries now, or {@code null} while it carries none. */
        @Nullable String record(int length) {
            int bit = Math.min(length, MAX_LENGTH);
            int word = bit / Long.SIZE;
            long mask = 1L << (bit % Long.SIZE);
            if ((words.get(word) & mask) == 0 && (words.getAndAccumulate(word, mask, (a, b) -> a | b) & mask) == 0) {
                distinct.incrementAndGet();
            }
            return distinct.get() >= Advice.IN_LIST_LENGTHS ? ADVISORY : null;
        }
    }

    /**
     * Records that the statement of the shape rendered its list with the given length, and returns the advice the
     * statement carries as a result, or {@code null} while the shape has rendered with too few lengths to carry any.
     *
     * @param shapeId the identity of the statement's shape; {@code 0}, a statement without one, is not recorded.
     * @param length the number of values the list rendered with.
     * @return the advice, or {@code null}.
     */
    static @Nullable String observe(long shapeId, int length) {
        if (shapeId == 0) {
            return null;
        }
        var lengths = SHAPES.get(shapeId);
        if (lengths == null) {
            if (SHAPES.size() >= AdviceLog.MAX_REPORTED) {
                SHAPES.clear();
            }
            lengths = SHAPES.computeIfAbsent(shapeId, ignore -> new Lengths());
        }
        return lengths.record(length);
    }

    /**
     * Returns how many distinct lengths the shape has rendered its list with, as a fact for a slow line.
     *
     * @param shapeId the identity of the statement's shape.
     * @return the distinct lengths; zero for a shape without a recorded list.
     */
    static int lengths(long shapeId) {
        var lengths = shapeId == 0 ? null : SHAPES.get(shapeId);
        return lengths == null ? 0 : lengths.distinct.get();
    }

    /** Forgets every length seen. */
    static void reset() {
        SHAPES.clear();
    }
}
