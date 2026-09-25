create table substrate_mix (
    id text primary key check (length(id) = 36),
    name text not null,
    notes text,
    substrate text not null check (json_valid(substrate))
);
