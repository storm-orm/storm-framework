-- A block and a tagged function body that hold semicolons, each of which the script runner keeps as one statement.
create table item (id integer primary key, name varchar(255));

DO $$ BEGIN
    insert into item (id, name) values (1, 'first');
    insert into item (id, name) values (2, 'second');
END $$;

create function item_count() returns bigint as $body$
    select count(*) from item;
$body$ language sql;
