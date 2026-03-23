PRAGMA foreign_keys = OFF;

DROP TABLE IF EXISTS visit;
DROP TABLE IF EXISTS vet_specialty;
DROP TABLE IF EXISTS pet;
DROP TABLE IF EXISTS pet_type;
DROP TABLE IF EXISTS specialty;
DROP TABLE IF EXISTS vet;
DROP TABLE IF EXISTS owner;
DROP VIEW IF EXISTS owner_view;
DROP VIEW IF EXISTS visit_view;

PRAGMA foreign_keys = ON;

CREATE TABLE owner (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    address VARCHAR(255),
    city VARCHAR(255),
    telephone VARCHAR(255),
    version INTEGER DEFAULT 0
);

CREATE TABLE pet_type (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(255),
    description VARCHAR(255),
    UNIQUE(name)
);

CREATE TABLE pet (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(255),
    birth_date DATE,
    owner_id INTEGER REFERENCES owner(id),
    type_id INTEGER REFERENCES pet_type(id)
);

-- For specialty, we retain an integer type so you can explicitly insert IDs.
CREATE TABLE specialty (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255),
    UNIQUE(name)
);

CREATE TABLE vet (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    first_name VARCHAR(255),
    last_name VARCHAR(255)
);

CREATE TABLE vet_specialty (
    vet_id INTEGER NOT NULL,
    specialty_id INTEGER NOT NULL,
    PRIMARY KEY (vet_id, specialty_id),
    FOREIGN KEY (vet_id) REFERENCES vet(id),
    FOREIGN KEY (specialty_id) REFERENCES specialty(id)
);

CREATE TABLE visit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    visit_date DATE,
    description VARCHAR(255),
    pet_id INTEGER NOT NULL REFERENCES pet(id),
    "timestamp" TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE VIEW owner_view AS
    SELECT * FROM owner;

CREATE VIEW visit_view AS
    SELECT visit_date, description, pet_id, "timestamp" FROM visit;

-- Data Inserts

INSERT INTO vet (first_name, last_name) VALUES ('James', 'Carter');
INSERT INTO vet (first_name, last_name) VALUES ('Helen', 'Leary');
INSERT INTO vet (first_name, last_name) VALUES ('Linda', 'Douglas');
INSERT INTO vet (first_name, last_name) VALUES ('Rafael', 'Ortega');
INSERT INTO vet (first_name, last_name) VALUES ('Henry', 'Stevens');
INSERT INTO vet (first_name, last_name) VALUES ('Sharon', 'Jenkins');

INSERT INTO specialty (id, name) VALUES (1, 'radiology');
INSERT INTO specialty (id, name) VALUES (2, 'surgery');
INSERT INTO specialty (id, name) VALUES (3, 'dentistry');

INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (2, 1);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (3, 2);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (3, 3);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (4, 2);
INSERT INTO vet_specialty (vet_id, specialty_id) VALUES (5, 1);

INSERT INTO pet_type (name) VALUES ('cat');
INSERT INTO pet_type (name) VALUES ('dog');
INSERT INTO pet_type (name) VALUES ('lizard');
INSERT INTO pet_type (name) VALUES ('snake');
INSERT INTO pet_type (name) VALUES ('bird');
INSERT INTO pet_type (name) VALUES ('hamster');

INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Betty', 'Davis', '638 Cardinal Ave.', 'Sun Prairie', '6085551749');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('George', 'Franklin', '110 W. Liberty St.', 'Madison', '6085551023');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Eduardo', 'Rodriquez', '2693 Commerce St.', 'McFarland', '6085558763');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Harold', 'Davis', '563 Friendly St.', 'Windsor', '6085553198');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Peter', 'McTavish', '2387 S. Fair Way', 'Madison', '6085552765');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Jean', 'Coleman', '105 N. Lake St.', 'Monona', '6085552654');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Jeff', 'Black', '1450 Oak Blvd.', 'Monona', '6085555387');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Maria', 'Escobito', '345 Maple St.', 'Madison', '6085557683');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('David', 'Schroeder', '2749 Blackhawk Trail', 'Madison', '6085559435');
INSERT INTO owner (first_name, last_name, address, city, telephone) VALUES ('Carlos', 'Estaban', '2335 Independence La.', 'Waunakee', '6085555487');

INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Leo', '2020-09-07', 1, 1);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Basil', '2022-08-06', 2, 6);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Rosy', '2021-04-17', 3, 2);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Jewel', '2020-03-07', 3, 2);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Iggy', '2020-11-30', 4, 3);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('George', '2020-01-20', 5, 4);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Samantha', '2022-09-04', 6, 1);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Max', '2022-09-04', 6, 1);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Lucky', '2021-08-06', 7, 5);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Mulligan', '2007-02-24', 8, 2);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Freddy', '2020-03-09', 9, 5);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Lucky', '2020-06-24', 10, 2);
INSERT INTO pet (name, birth_date, owner_id, type_id) VALUES ('Sly', '2022-06-08', NULL, 1);

INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-01', 'rabies shot', 7);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-02', 'rabies shot', 8);
INSERT INTO visit (visit_date, description, pet_id) VALUES ('2023-01-03', 'neutered', 8);
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

-- UUID support tests (UUID stored as TEXT in SQLite)
DROP TABLE IF EXISTS api_key;
CREATE TABLE api_key (id TEXT PRIMARY KEY, name TEXT NOT NULL, external_reference TEXT);

INSERT INTO api_key (id, name, external_reference) VALUES ('550e8400-e29b-41d4-a716-446655440000', 'Default Key', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11');
INSERT INTO api_key (id, name, external_reference) VALUES ('6ba7b810-9dad-11d1-80b4-00c04fd430c8', 'Secondary Key', NULL);

-- Polymorphic tables for sealed type hierarchy tests.

-- Pattern A: Single-Table Inheritance
DROP TABLE IF EXISTS animal;
CREATE TABLE animal (id INTEGER PRIMARY KEY AUTOINCREMENT, dtype VARCHAR(50) NOT NULL, name VARCHAR(255), indoor BOOLEAN, weight INTEGER);

INSERT INTO animal (dtype, name, indoor) VALUES ('Cat', 'Whiskers', 1);
INSERT INTO animal (dtype, name, indoor) VALUES ('Cat', 'Luna', 0);
INSERT INTO animal (dtype, name, weight) VALUES ('Dog', 'Rex', 30);
INSERT INTO animal (dtype, name, weight) VALUES ('Dog', 'Max', 15);

-- Pattern C: Joined Table Inheritance
PRAGMA foreign_keys = OFF;
DROP TABLE IF EXISTS joined_cat;
DROP TABLE IF EXISTS joined_dog;
DROP TABLE IF EXISTS joined_animal;
PRAGMA foreign_keys = ON;

CREATE TABLE joined_animal (id INTEGER PRIMARY KEY AUTOINCREMENT, dtype VARCHAR(50) NOT NULL, name VARCHAR(255));
CREATE TABLE joined_cat (id INTEGER NOT NULL PRIMARY KEY REFERENCES joined_animal(id), indoor BOOLEAN);
CREATE TABLE joined_dog (id INTEGER NOT NULL PRIMARY KEY REFERENCES joined_animal(id), weight INTEGER);

INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Whiskers');
INSERT INTO joined_cat (id, indoor) VALUES (1, 1);
INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Luna');
INSERT INTO joined_cat (id, indoor) VALUES (2, 0);
INSERT INTO joined_animal (dtype, name) VALUES ('JoinedDog', 'Rex');
INSERT INTO joined_dog (id, weight) VALUES (3, 30);

-- Pattern D: Joined Table Inheritance without @Discriminator
PRAGMA foreign_keys = OFF;
DROP TABLE IF EXISTS nodsc_cat;
DROP TABLE IF EXISTS nodsc_dog;
DROP TABLE IF EXISTS nodsc_bird;
DROP TABLE IF EXISTS nodsc_animal;
PRAGMA foreign_keys = ON;

CREATE TABLE nodsc_animal (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(255));
CREATE TABLE nodsc_cat (id INTEGER NOT NULL PRIMARY KEY REFERENCES nodsc_animal(id), indoor BOOLEAN);
CREATE TABLE nodsc_dog (id INTEGER NOT NULL PRIMARY KEY REFERENCES nodsc_animal(id), weight INTEGER);
CREATE TABLE nodsc_bird (id INTEGER NOT NULL PRIMARY KEY REFERENCES nodsc_animal(id));

INSERT INTO nodsc_animal (name) VALUES ('Whiskers');
INSERT INTO nodsc_cat (id, indoor) VALUES (1, 1);
INSERT INTO nodsc_animal (name) VALUES ('Luna');
INSERT INTO nodsc_cat (id, indoor) VALUES (2, 0);
INSERT INTO nodsc_animal (name) VALUES ('Rex');
INSERT INTO nodsc_dog (id, weight) VALUES (3, 30);
INSERT INTO nodsc_animal (name) VALUES ('Tweety');
INSERT INTO nodsc_bird (id) VALUES (4);
