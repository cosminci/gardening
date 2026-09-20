create table plant (
    id text primary key,
    species text not null,
    nickname text,
    location text not null,
    status text not null check (status in ('Active', 'Archived')),
    substrate text not null
);

create table operation (
    id text primary key,
    plant_id text not null references plant (id),
    date text not null,
    kind text not null check (kind in ('Care', 'Repot')),
    payload text not null check (json_valid(payload))
);

create unique index operation_plant_date_idx on operation (plant_id, date);
