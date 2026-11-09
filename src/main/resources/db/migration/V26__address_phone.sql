-- Optional contact phone of a delivery address (10 digits, validated by the API).
alter table address add column phone varchar(10);
