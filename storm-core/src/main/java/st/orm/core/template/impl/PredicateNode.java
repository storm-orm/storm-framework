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
import static st.orm.core.template.TemplateString.wrap;

import java.util.ArrayList;
import java.util.List;
import st.orm.Match;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.SearchExpression;

/**
 * The boolean structure of a predicate: its terms and the operators combining them. A predicate renders as one
 * template, which is all a plain query needs; a query with a semi-join or anti-join places each term by the tables it
 * names, which takes the structure.
 *
 * @since 1.16
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
     * The words of a search, kept apart until the query is built, since each field takes them by its minimum length,
     * and the short words are planned by the length the dialect's substring index serves.
     *
     * @param terms the terms, as the input holds them, without the ignored ones.
     * @param start the whole input: its terms joined by one space, the ignored ones included, since they stand in the
     *              value as typed.
     * @param fields the text columns to search with their match forms, at least one.
     * @param suggest {@code true} for {@code suggest}, whose input without a term matches no rows; {@code false} for
     *                {@code search}, whose input without a term matches every row.
     */
    record Search(List<String> terms, String start, List<Match<?>> fields, boolean suggest) implements PredicateNode {

        public Search {
            terms = List.copyOf(terms);
            requireNonNull(start, "start");
            fields = List.copyOf(fields);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("%s needs at least one field to search.".formatted(suggest ? "suggest" : "search"));
            }
        }

        /**
         * Returns the search as the predicates it renders. A field that a word reaches by the field's minimum length
         * takes every word in its form: an AND over the words, each matched in at least one of those fields. A field
         * that no word reaches takes the whole input in its fallback, where the input reaches the fallback's minimum
         * length, as an alternative to the words; a field without a fallback then takes no part. An input that no
         * field takes matches no rows. A word shorter than the given length, or than the fields' minimum length, is
         * residual next to one that reaches it, tested only on the rows the longer word found. An input without a
         * term matches every row for {@code search} and no rows for {@code suggest}.
         *
         * @param minimumLength the shortest term the substring index serves, {@code 0} where no index serves one.
         */
        PredicateNode resolve(int minimumLength) {
            if (terms.isEmpty()) {
                return suggest ? NO_ROWS : new Term(TemplateString.of("1 = 1"), true);
            }
            var reached = new ArrayList<Match<?>>(fields.size());
            var fallbacks = new ArrayList<PredicateNode>(fields.size());
            for (Match<?> field : fields) {
                if (terms.stream().anyMatch(term -> reaches(term, field.minLength()))) {
                    reached.add(field);
                } else {
                    var fallback = field.fallback();
                    if (fallback != null && reaches(start, fallback.minLength())) {
                        fallbacks.add(match(fallback, start));
                    }
                }
            }
            var alternatives = new ArrayList<PredicateNode>(2);
            if (!reached.isEmpty()) {
                alternatives.add(words(reached, minimumLength));
            }
            if (!fallbacks.isEmpty()) {
                alternatives.add(or(fallbacks));
            }
            return alternatives.isEmpty() ? NO_ROWS : or(alternatives);
        }

        private static final PredicateNode NO_ROWS = new Term(TemplateString.of("1 <> 1"), true);

        /**
         * Returns the predicate matching every word in at least one of the fields a word reaches. A word shorter than
         * the planning length is tested on the rows the long words found; alone, nothing narrows the rows for it.
         */
        private PredicateNode words(List<Match<?>> reached, int minimumLength) {
            int fieldLength = reached.stream().mapToInt(Match::minLength).min().orElse(0);
            int length = Math.max(minimumLength, fieldLength);
            boolean narrowed = terms.stream().anyMatch(term -> reaches(term, length));
            var scopes = new ArrayList<PredicateNode>(terms.size());
            terms.stream().filter(term -> reaches(term, length)).forEach(term -> scopes.add(new Scope(word(reached, term), false)));
            terms.stream().filter(term -> !reaches(term, length)).forEach(term -> scopes.add(new Scope(word(reached, term), narrowed)));
            return and(scopes);
        }

        /**
         * Returns the predicate matching a term in at least one of the fields, each in its match form. Each term is
         * matched on its own, so on a semi-joined table the terms may match different related rows.
         */
        private static PredicateNode word(List<Match<?>> fields, String term) {
            var matches = new ArrayList<PredicateNode>(fields.size());
            for (Match<?> field : fields) {
                matches.add(match(field, term));
            }
            return or(matches);
        }

        /**
         * Returns the predicate matching the text in the field's form. A word start may render as several predicates,
         * so it is grouped when combined.
         */
        private static PredicateNode match(Match<?> field, String text) {
            return new Term(wrap(new SearchExpression(field, text)), MatchForm.of(field) != MatchForm.WORD_START);
        }

        private static boolean reaches(String term, int minimumLength) {
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
            case Group(var ignore, var operands) -> {
                // Asked for every predicate a query is built with, so a plain loop rather than a stream.
                for (var operand : operands) {
                    if (holdsSearch(operand)) {
                        yield true;
                    }
                }
                yield false;
            }
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
