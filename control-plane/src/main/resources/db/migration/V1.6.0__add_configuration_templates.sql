create table configuration_templates (
    id varchar(255) not null,
    organization_id varchar(255) not null,
    name varchar(255) not null,
    description varchar(255),
    oauth2_configuration JSONB,
    primary key (id),
    unique (organization_id, name)
);
