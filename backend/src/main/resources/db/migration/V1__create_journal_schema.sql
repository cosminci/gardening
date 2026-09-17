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
    details text not null check (
        json_valid(details)
        and json_extract(details, '$.kind') in ('Care', 'Repot')
    )
);
