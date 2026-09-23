create table substrate_component (
    id text primary key check (length(id) = 36),
    name text not null,
    info text
);

create table pesticide (
    id text primary key check (length(id) = 36),
    name text not null,
    type text not null check (type in ('Fungicide', 'Insecticide', 'Treatment')),
    info text
);

create table plant (
    id text primary key,
    species text not null,
    nickname text,
    location text not null,
    status text not null check (status in ('Active', 'Archived')),
    substrate text not null check (json_valid(substrate))
);

create table operation (
    id text primary key,
    plant_id text not null references plant (id),
    date text not null,
    kind text not null check (kind in ('Care', 'Repot')),
    payload text not null check (json_valid(payload))
);

create index operation_plant_date_id_idx on operation (plant_id, date desc, id desc);

insert into substrate_component (id, name) values
    ('00000000-0000-4000-8000-000000000001', 'Kekkila universal peat'),
    ('00000000-0000-4000-8000-000000000002', 'Kekkila ericaceous peat'),
    ('00000000-0000-4000-8000-000000000003', 'Perlite'),
    ('00000000-0000-4000-8000-000000000004', 'Pine bark'),
    ('00000000-0000-4000-8000-000000000005', 'Sand 3-5 mm'),
    ('00000000-0000-4000-8000-000000000006', 'Sand 4-8 mm'),
    ('00000000-0000-4000-8000-000000000007', 'LECA');

insert into pesticide (id, name, type, info) values
    ('00000000-0000-4000-8001-000000000001', 'ORTIVA TOP', 'Fungicide', '1ml/L'),
    ('00000000-0000-4000-8001-000000000002', 'SWITCH 62.5 WG', 'Fungicide', null),
    ('00000000-0000-4000-8001-000000000003', 'VERTAB', 'Insecticide', '0.8ml/L'),
    ('00000000-0000-4000-8001-000000000004', 'SIMFONIA', 'Insecticide', 'organic'),
    ('00000000-0000-4000-8001-000000000005', 'SPRUZIT AF Neudorff', 'Insecticide', null),
    ('00000000-0000-4000-8001-000000000006', 'MOSPILAN 20SG', 'Insecticide', null),
    ('00000000-0000-4000-8001-000000000007', 'Neem oil + Catille soap', 'Insecticide', '5ml:5ml:1L'),
    ('00000000-0000-4000-8001-000000000008', 'H2O2', 'Treatment', null);
