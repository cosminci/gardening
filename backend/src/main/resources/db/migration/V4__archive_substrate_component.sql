alter table substrate_component add column status text not null default 'Active' check (status in ('Active', 'Archived'));
