create table substrate_component (
    id text primary key check (length(id) = 36),
    name text not null,
    info text,
    status text not null default 'Active' check (status in ('Active', 'Archived'))
);

create table pesticide (
    id text primary key check (length(id) = 36),
    name text not null,
    type text not null check (type in ('Fungicide', 'Insecticide', 'Treatment')),
    info text,
    status text not null default 'Active' check (status in ('Active', 'Archived'))
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

create table substrate_mix (
    id text primary key check (length(id) = 36),
    name text not null,
    notes text,
    substrate text not null check (json_valid(substrate))
);

create table plant_photo (
    id text primary key check (length(id) = 36),
    plant_id text not null references plant (id),
    captured_at text not null
);

create index plant_photo_plant_captured_id_idx on plant_photo (plant_id, captured_at desc, id desc);
