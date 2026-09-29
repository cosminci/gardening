create table photo_write_intent (
    key text primary key,
    operation text not null check (operation in ('Add', 'Remove')),
    status text not null default 'Pending' check (status in ('Pending', 'Done')),
    photo_id text check (photo_id is null or length(photo_id) = 36),
    plant_id text,
    captured_at text
);
