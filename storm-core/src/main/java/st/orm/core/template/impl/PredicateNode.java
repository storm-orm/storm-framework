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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import st.orm.core.template.TemplateString;

/**
 * The boolean structure of a predicate: its terms and the operators combining them. A predicate renders as one
 * template, which is all a plain query needs; a query with a semi-join or anti-join places each term by the tables it
 * names, which takes the structure.
 *
 * @since 1.15
 */
public sealed interface PredicateNode {

    /**
     * A term: a single comparison, or a template whose content is not inspected.
     *
     * @param template the term.
     * @param safe {@code false} for a template that may hold several terms, which is grouped in parentheses when it
     *             is combined with another term.
     */
    record Term(TemplateString template, boolean safe) implements PredicateNode {
        public Term {
            requireNonNull(template, "template");
        }
    }

    /**
     * Terms joined by one operator. Operands joined by the same operator are flattened into the group.
     *
     * @param and {@code true} for AND, {@code false} for OR.
     * @param operands the operands, at least two.
     */
    record Group(boolean and, List<PredicateNode> operands) implements PredicateNode {
        public Group {
            operands = List.copyOf(operands);
        }
    }

    /**
     * A predicate matched on its own. A comparison on a semi-joined table inside it is answered by a related row of its
     * own, rather than the related row that answers the rest of the condition. {@code search} marks each word with it.
     *
     * @param node the predicate.
     * @param residual {@code true} for a predicate that is tested only on the rows the rest of the condition found,
     *                 rather than read through an index of its own: a search term shorter than the dialect's substring
     *                 index serves, next to a longer term that narrows the rows.
     */
    record Scope(PredicateNode node, boolean residual) implements PredicateNode {
        public Scope {
            requireNonNull(node, "node");
        }

        Scope(PredicateNode node) {
            this(node, false);
        }
    }

    /**
     * The words of a search, kept apart until the query is built, since which of them a substring index serves depends
     * on the dialect.
     *
     * @param terms the terms, as the input holds them.
     * @param words per term, the predicate matching it in at least one of the paths.
     * @param suggest {@code true} to ignore the terms the index does not serve, and match no rows when none remains;
     *                {@code false} to match every term.
     */
    record Search(List<String> terms, List<PredicateNode> words, boolean suggest) implements PredicateNode {
        public Search {
            terms = List.copyOf(terms);
            words = List.copyOf(words);
            if (terms.size() != words.size()) {
                throw new IllegalArgumentException("A search holds one predicate per term.");
            }
        }

        /**
         * Returns the search as the predicates it renders for a dialect whose substring index serves terms of the
         * given length: an AND over the words. {@code search} keeps every word and marks a short one as residual when a
         * longer one narrows the rows; {@code suggest} keeps the long words only.
         *
         * @param minimumLength the shortest term the substring index serves, {@code 0} to treat every term alike.
         */
        PredicateNode resolve(int minimumLength) {
            var indexed = new ArrayList<PredicateNode>(words.size());
            var shortWords = new ArrayList<PredicateNode>(words.size());
            for (int i = 0; i < terms.size(); i++) {
                (indexed(terms.get(i), minimumLength) ? indexed : shortWords).add(words.get(i));
            }
            if (suggest) {
                if (indexed.isEmpty()) {
                    return new Term(TemplateString.of("1 <> 1"), true);
                }
                return and(indexed.stream().<PredicateNode>map(Scope::new).toList());
            }
            if (terms.isEmpty()) {
                return new Term(TemplateString.of("1 = 1"), true);
            }
            var scopes = new ArrayList<PredicateNode>(words.size());
            indexed.forEach(word -> scopes.add(new Scope(word, false)));
            // A short word is tested on the rows the long words found; alone, nothing narrows the rows for it.
            shortWords.forEach(word -> scopes.add(new Scope(word, !indexed.isEmpty())));
            return and(scopes);
        }

        /**
         * Returns whether the search reads every row whatever indexes the database holds: it matches every term, and
         * none of them is long enough for the substring index.
         *
         * @param minimumLength the shortest term the substring index serves.
         */
        boolean unindexed(int minimumLength) {
            return !suggest && minimumLength > 0 && !terms.isEmpty()
                    && terms.stream().noneMatch(term -> indexed(term, minimumLength));
        }

        private static boolean indexed(String term, int minimumLength) {
            return term.codePointCount(0, term.length()) >= minimumLength;
        }
    }

    /**
     * Returns the predicate with each search in it resolved for a dialect whose substring index serves terms of the
     * given length, or the predicate itself when it holds no search.
     *
     * @param node the predicate.
     * @param minimumLength the shortest term the substring index serves, {@code 0} to treat every term alike.
     * @return the resolved predicate.
     */
    static PredicateNode resolve(PredicateNode node, int minimumLength) {
        return switch (node) {
            case Term term -> term;
            case Search search -> search.resolve(minimumLength);
            case Scope(var inner, var residual) -> {
                var resolved = resolve(inner, minimumLength);
                yield resolved == inner ? node : new Scope(resolved, residual);
            }
            case Group(var and, var operands) -> {
                var resolved = new ArrayList<PredicateNode>(operands.size());
                boolean changed = false;
                for (var operand : operands) {
                    var r = resolve(operand, minimumLength);
                    changed |= r != operand;
                    resolved.add(r);
                }
                yield changed ? combine(and, resolved) : node;
            }
        };
    }

    /**
     * Returns whether the predicate holds a search.
     *
     * @param node the predicate.
     * @return {@code true} if a search is part of the predicate.
     */
    static boolean holdsSearch(PredicateNode node) {
        return switch (node) {
            case Term ignore -> false;
            case Search ignore -> true;
            case Scope(var inner, var ignore) -> holdsSearch(inner);
            case Group(var ignore, var operands) -> operands.stream().anyMatch(PredicateNode::holdsSearch);
        };
    }

    /**
     * Returns whether the predicate holds a search that reads every row whatever indexes the database holds.
     *
     * @param node the predicate.
     * @param minimumLength the shortest term the substring index serves.
     * @return {@code true} if a search in the predicate has no term the substring index serves.
     */
    static boolean holdsUnindexedSearch(PredicateNode node, int minimumLength) {
        return switch (node) {
            case Term ignore -> false;
            case Search search -> search.unindexed(minimumLength);
            case Scope(var inner, var ignore) -> holdsUnindexedSearch(inner, minimumLength);
            case Group(var ignore, var operands) -> operands.stream().anyMatch(operand -> holdsUnindexedSearch(operand, minimumLength));
        };
    }

    /**
     * Returns the operands joined by AND, flattening operands that are themselves joined by AND.
     */
    static PredicateNode and(List<PredicateNode> operands) {
        return combine(true, operands);
    }

    /**
     * Returns the operands joined by OR, flattening operands that are themselves joined by OR.
     */
    static PredicateNode or(List<PredicateNode> operands) {
        return combine(false, operands);
    }

    private static PredicateNode combine(boolean and, List<PredicateNode> operands) {
        var flat = new ArrayList<PredicateNode>(operands.size());
        for (var operand : operands) {
            if (operand instanceof Group(var operandAnd, var nested) && operandAnd == and) {
                flat.addAll(nested);
            } else {
                flat.add(operand);
            }
        }
        if (flat.size() == 1) {
            return flat.getFirst();
        }
        return new Group(and, flat);
    }

    /**
     * Returns the predicate as one template, with a group in parentheses wherever it is an operand of another
     * operator, and a template that may hold several terms in parentheses wherever it is combined.
     *
     * @return the predicate as a template.
     */
    default TemplateString toTemplate() {
        return switch (this) {
            case Term term -> term.template();
            case Scope(var node, var ignore) -> node.toTemplate();
            case Search search -> search.resolve(0).toTemplate();
            case Group(var and, var operands) -> {
                var parts = new ArrayList<TemplateString>(operands.size() * 2);
                for (var operand : operands) {
                    if (!parts.isEmpty()) {
                        parts.add(TemplateString.of(and ? " AND " : " OR "));
                    }
                    parts.add(operand.grouped() ? TemplateString.combine(TemplateString.of("("), operand.toTemplate(), TemplateString.of(")")) : operand.toTemplate());
                }
                yield TemplateString.combine(parts);
            }
        };
    }

    /**
     * Returns whether the predicate needs parentheses as an operand of a group: a group, which is always joined by the
     * other operator once flattened, or a template that may hold several terms.
     */
    private boolean grouped() {
        return switch (this) {
            case Term term -> !term.safe();
            case Scope(var node, var ignore) -> node.grouped();
            case Search search -> search.resolve(0).grouped();
            case Group ignore -> true;
        };
    }
}
