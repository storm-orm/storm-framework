drop table if exists appointment_report_review CASCADE;
drop table if exists appointment_report CASCADE;
drop table if exists appointment CASCADE;
drop table if exists tenant CASCADE;
drop table if exists city CASCADE;
drop table if exists owner CASCADE;
drop table if exists owner_nullable_address CASCADE;
drop table if exists owner_primary_pet CASCADE;
drop table if exists pet CASCADE;
drop table if exists pet_extension CASCADE;
drop table if exists pet_type CASCADE;
drop table if exists specialty CASCADE;
drop table if exists vet CASCADE;
drop table if exists vet_badge CASCADE;
drop table if exists vet_specialty CASCADE;
drop table if exists visit CASCADE;
drop table if exists self_ref_node CASCADE;
drop table if exists cross_package_holder CASCADE;
drop table if exists cross_package_owner CASCADE;
drop table if exists user_score CASCADE;
drop table if exists app_user CASCADE;
drop table if exists country CASCADE;
drop table if exists country_city CASCADE;

create table city (id integer auto_increment, name varchar(255) not null, primary key (id));
create table owner (id integer auto_increment, first_name varchar(255) not null, last_name varchar(255) not null, address varchar(255) not null, city_id integer, telephone varchar(255), primary key (id), version integer default 0 not null);
create table pet (id integer auto_increment, name varchar(255) not null, birth_date date not null, owner_id integer, type_id integer not null, primary key (id));
create table pet_type (id integer, name varchar(255) not null, primary key (id));
create table specialty (id integer auto_increment, name varchar(255) not null, primary key (id));
create table vet (id integer auto_increment, first_name varchar(255) not null, last_name varchar(255) not null, primary key (id));
create table vet_badge (id integer auto_increment, label varchar(255), vet_id integer, primary key (id));
create table vet_specialty (vet_id integer, specialty_id integer not null, primary key (vet_id, specialty_id));
create table visit (id integer auto_increment, visit_date date not null, description varchar(255), vet_id integer null, specialty_id integer null, pet_id integer not null, "timestamp" timestamp default CURRENT_TIMESTAMP not null, primary key (id));
create table pet_extension (pet_id integer not null, notes varchar(255), primary key (pet_id));
create table owner_nullable_address (id integer auto_increment, first_name varchar(255) not null, last_name varchar(255) not null, address varchar(255), city_id integer, telephone varchar(255), version integer default 0 not null, primary key (id));
alter table owner_nullable_address add constraint owner_nullable_address_city_fk foreign key (city_id) references city (id);
create table owner_primary_pet (owner_id integer not null, pet_id integer not null, primary key (owner_id));
alter table owner add constraint owner_city_fk foreign key (city_id) references city (id);
alter table pet_extension add constraint pet_extension_pet_fk foreign key (pet_id) references pet (id);
alter table owner_primary_pet add constraint owner_primary_pet_owner_fk foreign key (owner_id) references owner (id);
alter table owner_primary_pet add constraint owner_primary_pet_pet_fk foreign key (pet_id) references pet (id);
alter table pet add constraint pet_owner_fk foreign key (owner_id) references owner (id);
alter table pet add constraint pet_pet_type_fk foreign key (type_id) references pet_type (id);
alter table vet_badge add constraint vet_badge_vet_fk foreign key (vet_id) references vet (id);
alter table vet_specialty add constraint vet_specialty_specialty_fk foreign key (specialty_id) references specialty (id);
alter table vet_specialty add constraint vet_specialty_vet_fk foreign key (vet_id) references vet (id);
alter table visit add constraint visit_pet_fk foreign key (pet_id) references pet (id);
alter table visit add constraint visit_vet_specialty_fk foreign key (vet_id, specialty_id) references vet_specialty (vet_id, specialty_id);
create table tenant (id integer auto_increment, name varchar(255) not null, owner_id integer not null, city_id integer not null, primary key (id));
alter table tenant add constraint tenant_owner_fk foreign key (owner_id) references owner (id);
alter table tenant add constraint tenant_city_fk foreign key (city_id) references city (id);
-- scheduled_at has second precision: a database round-trip drops the sub-second part of the in-memory value.
create table appointment (id integer auto_increment, description varchar(255) not null, scheduled_at timestamp(0) not null, primary key (id));
create table appointment_report (appointment_id integer not null, report varchar(255) not null, primary key (appointment_id));
create table appointment_report_review (id integer auto_increment, appointment_report_id integer not null, review varchar(255) not null, primary key (id));
alter table appointment_report add constraint appointment_report_appointment_fk foreign key (appointment_id) references appointment (id);
alter table appointment_report_review add constraint appointment_report_review_report_fk foreign key (appointment_report_id) references appointment_report (appointment_id);
create table self_ref_node (id integer auto_increment, name varchar(255), parent_id integer, primary key (id));
alter table self_ref_node add constraint self_ref_node_parent_fk foreign key (parent_id) references self_ref_node (id);
create table cross_package_owner (id integer auto_increment, label varchar(255), score integer, primary key (id));
create table cross_package_holder (id integer auto_increment, label varchar(255), score integer, owner_id integer, primary key (id));
alter table cross_package_holder add constraint cross_package_holder_owner_fk foreign key (owner_id) references cross_package_owner (id);
-- The country table is reachable from itself: it refers to its capital and its largest city, and a city refers back to
-- the country it lies in. The foreign keys point both ways, so the constraints are added once both tables exist.
create table country_city (id integer auto_increment, name varchar(255) not null, country_id integer not null, primary key (id));
create table country (id integer auto_increment, name varchar(255) not null, capital_id integer, largest_city_id integer, primary key (id));
alter table country add constraint country_capital_fk foreign key (capital_id) references country_city (id);
alter table country add constraint country_largest_city_fk foreign key (largest_city_id) references country_city (id);
alter table country_city add constraint country_city_country_fk foreign key (country_id) references country (id);
create table app_user (id integer auto_increment, name varchar(255) not null, country_id integer not null, primary key (id));
alter table app_user add constraint app_user_country_fk foreign key (country_id) references country (id);
create table user_score (id integer auto_increment, user_id integer not null, score_date date not null, score double not null, primary key (id));
alter table user_score add constraint user_score_user_fk foreign key (user_id) references app_user (id);

create view owner_view as select * from owner;
create view visit_view as select visit_date, description, pet_id, "timestamp" from visit;

INSERT INTO city (name) VALUES ('Sun Paririe');
INSERT INTO city (name) VALUES ('Madison');
INSERT INTO city (name) VALUES ('McFarland');
INSERT INTO city (name) VALUES ('Windsor');
INSERT INTO city (name) VALUES ('Monona');
INSERT INTO city (name) VALUES ('Waunakee');
INSERT INTO vet (first_name, last_name) VALUES ('James', 'Carter');
INSERT INTO vet (first_name, last_name) VALUES ('Helen', 'Leary');
INSERT INTO vet (first_name, last_name) VALUES ('Linda', 'Douglas');
INSERT INTO vet (first_name, last_name) VALUES ('Rafael', 'Ortega');
INSERT INTO vet (first_name, last_name) VALUES ('Henry', 'Stevens');
INSERT INTO vet (first_name, last_name) VALUES ('Sharon', 'Jenkins');

INSERT INTO specialty (name) VALUES ('radiology');
INSERT INTO specialty (name) VALUES ('surgery');
INSERT INTO specialty (name) VALUES ('dentistry');

INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (2, 1);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (3, 2);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (3, 3);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (4, 2);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (5, 1);

INSERT INTO pet_type (id, name) VALUES (0, 'cat');
INSERT INTO pet_type (id, name) VALUES (1, 'dog');
INSERT INTO pet_type (id, name) VALUES (2, 'lizard');
INSERT INTO pet_type (id, name) VALUES (3, 'snake');
INSERT INTO pet_type (id, name) VALUES (4, 'bird');
INSERT INTO pet_type (id, name) VALUES (5, 'hamster');

INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Betty', 'Davis', '638 Cardinal Ave.', 1, '6085551749');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('George', 'Franklin', '110 W. Liberty St.', 2, '6085551023');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Eduardo', 'Rodriquez', '2693 Commerce St.', 3, '6085558763');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Harold', 'Davis', '563 Friendly St.', 4, '6085553198');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Peter', 'McTavish', '2387 S. Fair Way', 2, '6085552765');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Jean', 'Coleman', '105 N. Lake St.', 5, '6085552654');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Jeff', 'Black', '1450 Oak Blvd.', 5, '6085555387');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Maria', 'Escobito', '345 Maple St.', 2, '6085557683');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('David', 'Schroeder', '2749 Blackhawk Trail', 2, '6085559435');
INSERT INTO owner (first_name, last_name, address, city_id, telephone) VALUES ('Carlos', 'Estaban', '2335 Independence La.', 6, '6085555487');

INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Leo', '2020-09-07', 1, 0);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Basil', '2022-08-06', 2, 5);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Rosy', '2021-04-17', 3, 1);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Jewel', '2020-03-07', 3, 1);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Iggy', '2020-11-30', 4, 2);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('George', '2020-01-20', 5, 3);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Samantha', '2022-09-04', 6, 0);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Max', '2022-09-04', 6, 0);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Lucky', '2021-08-06', 7, 4);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Mulligan', '2007-02-24', 8, 1);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Freddy', '2020-03-09', 9, 4);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Lucky', '2020-06-24', 10, 1);
INSERT INTO pet (name, birth_date, owner_id , type_id) VALUES ('Sly', '2022-06-08', NULL, 0);

INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-01', 'rabies shot', 7);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-02', 'rabies shot', 8);
INSERT INTO visit (visit_date, description, pet_id, vet_id, specialty_id) VALUES ('2023-01-03', 'neutered', 8, 3, 2);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-04', 'spayed', 1);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-04', 'spayed', 2);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-06', 'spayed', 3);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-08', 'neutered', 4);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-08', 'spayed', 1);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-08', 'neutered', 2);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-09', 'spayed', 4);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-12', 'rabies shot', 5);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-13', 'spayed', 6);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-13', 'rabies shot', 6);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-13', 'spayed', 7);

INSERT INTO pet_extension (pet_id, notes) VALUES (1, 'Good cat');
INSERT INTO pet_extension (pet_id, notes) VALUES (3, 'Friendly dog');
INSERT INTO pet_extension (pet_id, notes) VALUES (7, 'Indoor cat');

-- Polymorphic tables for sealed type hierarchy tests.

-- Pattern A: Single-Table Inheritance
drop table if exists adoption CASCADE;
drop table if exists animal CASCADE;
create table animal (id integer auto_increment, dtype varchar(50) not null, name varchar(255), indoor boolean, weight integer, primary key (id));

INSERT INTO animal (dtype, name, indoor) VALUES ('Cat', 'Whiskers', true);
INSERT INTO animal (dtype, name, indoor) VALUES ('Cat', 'Luna', false);
INSERT INTO animal (dtype, name, weight) VALUES ('Dog', 'Rex', 30);
INSERT INTO animal (dtype, name, weight) VALUES ('Dog', 'Max', 15);

create table adoption (id integer auto_increment, animal_id integer not null, primary key (id));
alter table adoption add constraint adoption_animal_fk foreign key (animal_id) references animal (id);

INSERT INTO adoption (animal_id) VALUES (1);
INSERT INTO adoption (animal_id) VALUES (3);

-- Pattern B: Polymorphic FK
drop table if exists comment CASCADE;
drop table if exists post CASCADE;
drop table if exists photo CASCADE;
create table post (id integer auto_increment, title varchar(255) not null, primary key (id));
create table photo (id integer auto_increment, url varchar(255) not null, primary key (id));
create table comment (id integer auto_increment, text varchar(255) not null, target_type varchar(50) not null, target_id integer not null, primary key (id));

INSERT INTO post (title) VALUES ('Hello World');
INSERT INTO post (title) VALUES ('Second Post');
INSERT INTO photo (url) VALUES ('photo1.jpg');
INSERT INTO photo (url) VALUES ('photo2.jpg');
INSERT INTO comment (text, target_type, target_id) VALUES ('Nice post!', 'post', 1);
INSERT INTO comment (text, target_type, target_id) VALUES ('Great photo!', 'photo', 1);
INSERT INTO comment (text, target_type, target_id) VALUES ('Love it!', 'post', 2);

-- Pattern C: Joined Table Inheritance
drop table if exists joined_adoption CASCADE;
drop table if exists joined_cat CASCADE;
drop table if exists joined_dog CASCADE;
drop table if exists joined_animal CASCADE;
create table joined_animal (id integer auto_increment, dtype varchar(50) not null, name varchar(255), primary key (id));
create table joined_cat (id integer not null, indoor boolean not null, primary key (id));
create table joined_dog (id integer not null, weight integer not null, primary key (id));
alter table joined_cat add constraint joined_cat_animal_fk foreign key (id) references joined_animal (id);
alter table joined_dog add constraint joined_dog_animal_fk foreign key (id) references joined_animal (id);

INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Whiskers');
INSERT INTO joined_cat (id, indoor) VALUES (1, true);
INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Luna');
INSERT INTO joined_cat (id, indoor) VALUES (2, false);
INSERT INTO joined_animal (dtype, name) VALUES ('JoinedDog', 'Rex');
INSERT INTO joined_dog (id, weight) VALUES (3, 30);

create table joined_adoption (id integer auto_increment, animal_id integer not null, primary key (id));
alter table joined_adoption add constraint joined_adoption_animal_fk foreign key (animal_id) references joined_animal (id);

INSERT INTO joined_adoption (animal_id) VALUES (1);
INSERT INTO joined_adoption (animal_id) VALUES (3);

-- Pattern D: Joined Table Inheritance without @Discriminator
drop table if exists nodsc_cat CASCADE;
drop table if exists nodsc_dog CASCADE;
drop table if exists nodsc_bird CASCADE;
drop table if exists nodsc_animal CASCADE;
create table nodsc_animal (id integer auto_increment, name varchar(255), primary key (id));
create table nodsc_cat (id integer not null, indoor boolean not null, primary key (id));
create table nodsc_dog (id integer not null, weight integer not null, primary key (id));
create table nodsc_bird (id integer not null, primary key (id));
alter table nodsc_cat add constraint nodsc_cat_animal_fk foreign key (id) references nodsc_animal (id);
alter table nodsc_dog add constraint nodsc_dog_animal_fk foreign key (id) references nodsc_animal (id);
alter table nodsc_bird add constraint nodsc_bird_animal_fk foreign key (id) references nodsc_animal (id);

INSERT INTO nodsc_animal (name) VALUES ('Whiskers');
INSERT INTO nodsc_cat (id, indoor) VALUES (1, true);
INSERT INTO nodsc_animal (name) VALUES ('Luna');
INSERT INTO nodsc_cat (id, indoor) VALUES (2, false);
INSERT INTO nodsc_animal (name) VALUES ('Rex');
INSERT INTO nodsc_dog (id, weight) VALUES (3, 30);
INSERT INTO nodsc_animal (name) VALUES ('Tweety');
INSERT INTO nodsc_bird (id) VALUES (4);

-- UUID support tests
drop table if exists api_key CASCADE;
create table api_key (id uuid, name varchar(255) not null, external_reference uuid, primary key (id));

INSERT INTO api_key (id, name, external_reference) VALUES ('550e8400-e29b-41d4-a716-446655440000', 'Default Key', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11');
INSERT INTO api_key (id, name, external_reference) VALUES ('6ba7b810-9dad-11d1-80b4-00c04fd430c8', 'Secondary Key', NULL);

-- Pattern E: Single-Table Inheritance with INTEGER discriminator
drop table if exists int_disc_animal CASCADE;
create table int_disc_animal (id integer auto_increment, dtype integer not null, name varchar(255), indoor boolean, weight integer, primary key (id));

INSERT INTO int_disc_animal (dtype, name, indoor) VALUES (1, 'Whiskers', true);
INSERT INTO int_disc_animal (dtype, name, indoor) VALUES (1, 'Luna', false);
INSERT INTO int_disc_animal (dtype, name, weight) VALUES (2, 'Rex', 30);
INSERT INTO int_disc_animal (dtype, name, weight) VALUES (2, 'Max', 15);

-- Pattern F: Single-Table Inheritance with CHAR discriminator
drop table if exists char_disc_animal CASCADE;
create table char_disc_animal (id integer auto_increment, dtype char(1) not null, name varchar(255), indoor boolean, weight integer, primary key (id));

INSERT INTO char_disc_animal (dtype, name, indoor) VALUES ('C', 'Whiskers', true);
INSERT INTO char_disc_animal (dtype, name, indoor) VALUES ('C', 'Luna', false);
INSERT INTO char_disc_animal (dtype, name, weight) VALUES ('D', 'Rex', 30);
INSERT INTO char_disc_animal (dtype, name, weight) VALUES ('D', 'Max', 15);

-- Tenant creates a diamond join graph: tenant -> owner -> city and tenant -> city.
INSERT INTO tenant (name, owner_id, city_id) VALUES ('Alpha', 1, 1);
INSERT INTO tenant (name, owner_id, city_id) VALUES ('Beta', 2, 2);

-- Self-referential chain for cyclic-reference navigation: node 3 -> parent 2 -> grandparent 1; node 4 is a root.
INSERT INTO self_ref_node (name, parent_id) VALUES ('root', NULL);
INSERT INTO self_ref_node (name, parent_id) VALUES ('mid', 1);
INSERT INTO self_ref_node (name, parent_id) VALUES ('leaf', 2);
INSERT INTO self_ref_node (name, parent_id) VALUES ('other', NULL);

-- Countries that lead back to themselves through their capital and largest city. Inserted country-first so the cities
-- have a country to point at, then linked up once those cities exist.
INSERT INTO country (name, capital_id, largest_city_id) VALUES ('Netherlands', NULL, NULL);
INSERT INTO country (name, capital_id, largest_city_id) VALUES ('Belgium', NULL, NULL);
INSERT INTO country_city (name, country_id) VALUES ('Amsterdam', 1);
INSERT INTO country_city (name, country_id) VALUES ('Rotterdam', 1);
INSERT INTO country_city (name, country_id) VALUES ('Brussels', 2);
INSERT INTO country_city (name, country_id) VALUES ('Antwerp', 2);
UPDATE country SET capital_id = 1, largest_city_id = 2 WHERE id = 1;
UPDATE country SET capital_id = 3, largest_city_id = 4 WHERE id = 2;

INSERT INTO app_user (name, country_id) VALUES ('Anna', 1);
INSERT INTO app_user (name, country_id) VALUES ('Bram', 2);

-- Two dates for the first user so an aggregate grouped by country and date returns one row per combination.
INSERT INTO user_score (user_id, score_date, score) VALUES (1, DATE '2026-07-25', 0.20);
INSERT INTO user_score (user_id, score_date, score) VALUES (1, DATE '2026-07-25', 0.40);
INSERT INTO user_score (user_id, score_date, score) VALUES (1, DATE '2026-07-26', 0.60);
INSERT INTO user_score (user_id, score_date, score) VALUES (2, DATE '2026-07-25', 0.80);
