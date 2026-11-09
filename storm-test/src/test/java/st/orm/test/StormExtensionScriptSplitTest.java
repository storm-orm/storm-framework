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
package st.orm.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.test.StormExtension.splitStatements;

import java.util.List;
import org.junit.jupiter.api.Test;

class StormExtensionScriptSplitTest {

    @Test
    void splitsOnSemicolons() {
        var statements = splitStatements("create table a (id int);\ncreate table b (id int);");
        assertEquals(List.of("create table a (id int)", "create table b (id int)"), statements);
    }

    @Test
    void ignoresSemicolonInLineComment() {
        var statements = splitStatements("""
                -- a comment; with a semicolon
                create table a (id int);
                """);
        assertEquals(1, statements.size());
        assertTrue(statements.getFirst().endsWith("create table a (id int)"));
    }

    @Test
    void ignoresSemicolonInBlockComment() {
        var statements = splitStatements("""
                /* block; comment;
                   spanning lines; */
                create table a (id int);
                create table b (id int);
                """);
        assertEquals(2, statements.size());
    }

    @Test
    void ignoresSemicolonInStringLiteral() {
        var statements = splitStatements("insert into a (name) values ('x; y');");
        assertEquals(List.of("insert into a (name) values ('x; y')"), statements);
    }

    @Test
    void handlesDoubledQuoteEscapeInStringLiteral() {
        var statements = splitStatements("insert into a (name) values ('it''s; fine');");
        assertEquals(List.of("insert into a (name) values ('it''s; fine')"), statements);
    }

    @Test
    void ignoresSemicolonInQuotedIdentifier() {
        var statements = splitStatements("create table \"weird;name\" (id int);");
        assertEquals(List.of("create table \"weird;name\" (id int)"), statements);
    }

    @Test
    void dropsCommentOnlyFragments() {
        var statements = splitStatements("""
                create table a (id int);
                -- trailing comment only
                """);
        assertEquals(1, statements.size());
    }

    @Test
    void dropsEmptyFragments() {
        var statements = splitStatements(";;create table a (id int);;");
        assertEquals(List.of("create table a (id int)"), statements);
    }

    @Test
    void keepsStatementWithoutTrailingSemicolon() {
        var statements = splitStatements("create table a (id int)");
        assertEquals(List.of("create table a (id int)"), statements);
    }

    @Test
    void keepsDollarQuotedBlockWhole() {
        var statements = splitStatements("""
                DO $$ BEGIN
                    perform 1;
                    perform 2;
                END $$;
                select 1;
                """);
        assertEquals(List.of("DO $$ BEGIN\n    perform 1;\n    perform 2;\nEND $$", "select 1"), statements);
    }

    @Test
    void keepsTaggedDollarQuoteWhole() {
        var statements = splitStatements("CREATE FUNCTION f() RETURNS int AS $body$ SELECT 1; $body$ LANGUAGE sql;");
        assertEquals(List.of("CREATE FUNCTION f() RETURNS int AS $body$ SELECT 1; $body$ LANGUAGE sql"), statements);
    }

    @Test
    void endsDollarQuoteOnlyAtItsOwnDelimiter() {
        var statements = splitStatements("select $my_tag1$ a; $$ b; $other$ c; $my_tag1$; select 2;");
        assertEquals(List.of("select $my_tag1$ a; $$ b; $other$ c; $my_tag1$", "select 2"), statements);
    }

    @Test
    void countsDollarQuoteAtTheStartOfTheScriptAsContent() {
        var statements = splitStatements("$$ a; b $$; select 1;");
        assertEquals(List.of("$$ a; b $$", "select 1"), statements);
    }

    @Test
    void runsUnterminatedDollarQuoteToTheEndOfTheScript() {
        var statements = splitStatements("DO $$ BEGIN; select 1;");
        assertEquals(List.of("DO $$ BEGIN; select 1;"), statements);
    }

    @Test
    void opensNoDollarQuoteAtPositionalParameter() {
        var statements = splitStatements("SELECT $1; SELECT $2$; SELECT 3;");
        assertEquals(List.of("SELECT $1", "SELECT $2$", "SELECT 3"), statements);
    }

    @Test
    void opensNoDollarQuoteInsideIdentifier() {
        var statements = splitStatements("SELECT a$b FROM t; SELECT a$$b; SELECT a_$$ FROM t; SELECT 4;");
        assertEquals(List.of("SELECT a$b FROM t", "SELECT a$$b", "SELECT a_$$ FROM t", "SELECT 4"), statements);
    }

    @Test
    void opensNoDollarQuoteAtLoneDollarSign() {
        var statements = splitStatements("select $ from a; select $name; select $");
        assertEquals(List.of("select $ from a", "select $name", "select $"), statements);
    }

    @Test
    void commentsAndStringsTakePrecedenceOverDollarQuotes() {
        var statements = splitStatements("""
                -- $$ not a quote;
                select 1;
                /* $$ nor this; */ select 2;
                select '$$'; select "$$"; select 5;
                """);
        assertEquals(List.of("-- $$ not a quote;\nselect 1", "/* $$ nor this; */ select 2", "select '$$'",
                "select \"$$\"", "select 5"), statements);
    }
}
