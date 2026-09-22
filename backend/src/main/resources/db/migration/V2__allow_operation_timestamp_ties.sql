drop index operation_plant_date_idx;

create index operation_plant_date_id_idx on operation (plant_id, date desc, id desc);
