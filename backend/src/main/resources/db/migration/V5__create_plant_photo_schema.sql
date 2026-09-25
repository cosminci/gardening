create table plant_photo (
    id text primary key check (length(id) = 36),
    plant_id text not null references plant (id),
    captured_at text not null
);

create index plant_photo_plant_captured_id_idx on plant_photo (plant_id, captured_at desc, id desc);
