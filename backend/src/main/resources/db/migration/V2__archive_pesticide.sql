alter table pesticide add column status text not null default 'Active' check (status in ('Active', 'Archived'));
