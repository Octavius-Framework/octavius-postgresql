-- Declared from the highest order down, which is also how PostgreSQL sorts the labels
CREATE TYPE ordo AS ENUM ('SENATORIUS', 'EQUESTER', 'PLEBEIUS');

CREATE TYPE residence AS (
    city     text,
    district text
);

CREATE TABLE citizens (
    id         serial PRIMARY KEY,
    nomen      text      NOT NULL,
    tribe      text      NOT NULL,
    ordo       ordo      NOT NULL,
    residence  residence NOT NULL,
    assessment bigint    NOT NULL,  -- in sesterces
    pater_id   int REFERENCES citizens (id)
);

-- The censors' marks against a citizen, each row one of several shapes
CREATE TABLE notae (
    id         serial PRIMARY KEY,
    citizen_id int         NOT NULL REFERENCES citizens (id),
    nota       dynamic_dto NOT NULL
);
