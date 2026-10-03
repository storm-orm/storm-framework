drop table if exists appointment_report CASCADE;
drop table if exists appointment CASCADE;
drop table if exists city CASCADE;
drop table if exists owner CASCADE;
drop table if exists pet CASCADE;
drop table if exists pet_type CASCADE;
drop table if exists specialty CASCADE;
drop table if exists vet CASCADE;
drop table if exists vet_specialty CASCADE;
drop table if exists visit CASCADE;

create table city (id integer auto_increment, name varchar(255) not null, primary key (id));
create table owner (id integer auto_increment, first_name varchar(255) not null, last_name varchar(255) not null, address varchar(255), city_id integer, telephone varchar(255), primary key (id), version integer default 0 not null);
create table pet (id integer auto_increment, name varchar(255) not null, birth_date date not null, owner_id integer, type_id integer not null, primary key (id));
create table pet_type (id integer, name varchar(255) not null, primary key (id));
create table specialty (id integer auto_increment, name varchar(255), primary key (id));
create table vet (id integer auto_increment, first_name varchar(255) not null, last_name varchar(255) not null, primary key (id));
create table vet_specialty (vet_id integer, specialty_id integer not null, primary key (vet_id, specialty_id));
create table visit (id integer auto_increment, visit_date date not null, description varchar(255), vet_id integer null, specialty_id integer null, pet_id integer not null, "timestamp" timestamp default CURRENT_TIMESTAMP, primary key (id));
alter table owner add constraint owner_city_fk foreign key (city_id) references city (id);
alter table pet add constraint pet_owner_fk foreign key (owner_id) references owner (id);
alter table pet add constraint pet_pet_type_fk foreign key (type_id) references pet_type (id);
alter table vet_specialty add constraint vet_specialty_specialty_fk foreign key (specialty_id) references specialty (id);
alter table vet_specialty add constraint vet_specialty_vet_fk foreign key (vet_id) references vet (id);
alter table visit add constraint visit_pet_fk foreign key (pet_id) references pet (id);
alter table visit add constraint visit_vet_specialty_fk foreign key (vet_id, specialty_id) references vet_specialty (vet_id, specialty_id);
-- scheduled_at has second precision: a database round-trip drops the sub-second part of the in-memory value.
create table appointment (id integer auto_increment, description varchar(255), scheduled_at timestamp(0), primary key (id));
create table appointment_report (appointment_id integer not null, report varchar(255), primary key (appointment_id));
alter table appointment_report add constraint appointment_report_appointment_fk foreign key (appointment_id) references appointment (id);
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

-- Polymorphic tables for sealed type hierarchy tests.

-- Pattern A: Single-Table Inheritance
drop table if exists adoption CASCADE;
drop table if exists animal CASCADE;
create table animal (id integer auto_increment, dtype varchar(50) not null, name varchar(255), indoor boolean, weight integer, primary key (id));

INSERT INTO animal (dtype, name, indoor) VALUES ('Cat', 'Whiskers', true);
INSERT INTO animal (dtype, name, indoor) VALUES ('Cat', 'Luna', false);
INSERT INTO animal (dtype, name, weight) VALUES ('Dog', 'Rex', 30);
INSERT INTO animal (dtype, name, weight) VALUES ('Dog', 'Max', 15);

create table adoption (id integer auto_increment, animal_id integer, primary key (id));
alter table adoption add constraint adoption_animal_fk foreign key (animal_id) references animal (id);

INSERT INTO adoption (animal_id) VALUES (1);
INSERT INTO adoption (animal_id) VALUES (3);

-- Pattern B: Polymorphic FK
drop table if exists comment CASCADE;
drop table if exists post CASCADE;
drop table if exists photo CASCADE;
create table post (id integer auto_increment, title varchar(255), primary key (id));
create table photo (id integer auto_increment, url varchar(255), primary key (id));
create table comment (id integer auto_increment, text varchar(255), target_type varchar(50), target_id integer, primary key (id));

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
create table joined_cat (id integer not null, indoor boolean, primary key (id));
create table joined_dog (id integer not null, weight integer, primary key (id));
alter table joined_cat add constraint joined_cat_animal_fk foreign key (id) references joined_animal (id);
alter table joined_dog add constraint joined_dog_animal_fk foreign key (id) references joined_animal (id);

INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Whiskers');
INSERT INTO joined_cat (id, indoor) VALUES (1, true);
INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Luna');
INSERT INTO joined_cat (id, indoor) VALUES (2, false);
INSERT INTO joined_animal (dtype, name) VALUES ('JoinedDog', 'Rex');
INSERT INTO joined_dog (id, weight) VALUES (3, 30);

create table joined_adoption (id integer auto_increment, animal_id integer, primary key (id));
alter table joined_adoption add constraint joined_adoption_animal_fk foreign key (animal_id) references joined_animal (id);

INSERT INTO joined_adoption (animal_id) VALUES (1);
INSERT INTO joined_adoption (animal_id) VALUES (3);

-- Pattern D: Joined Table Inheritance without @Discriminator
drop table if exists nodsc_cat CASCADE;
drop table if exists nodsc_dog CASCADE;
drop table if exists nodsc_bird CASCADE;
drop table if exists nodsc_animal CASCADE;
create table nodsc_animal (id integer auto_increment, name varchar(255), primary key (id));
create table nodsc_cat (id integer not null, indoor boolean, primary key (id));
create table nodsc_dog (id integer not null, weight integer, primary key (id));
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

-- A plain entity with a nullable boolean column, for the IS TRUE / IS FALSE predicates: neither matches the row
-- that leaves the column null, which is what distinguishes them from an equality comparison.
drop table if exists feature_flag CASCADE;
create table feature_flag (id integer auto_increment, name varchar(255), enabled boolean, primary key (id));

INSERT INTO feature_flag (name, enabled) VALUES ('rollout', true);
INSERT INTO feature_flag (name, enabled) VALUES ('legacy', false);
INSERT INTO feature_flag (name, enabled) VALUES ('undecided', null);
