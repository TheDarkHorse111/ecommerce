create table category (
    id         uuid          primary key,
    parent_id  uuid          null references category (id) on delete restrict,
    slug       varchar(100)  not null,
    path       varchar(500)  not null unique,
    sort_order int           not null default 0,
    active     boolean       not null default true,
    created_at timestamptz   not null,
    created_by varchar(64)   null,
    updated_at timestamptz   not null,
    updated_by varchar(64)   null,
    unique nulls not distinct (parent_id, slug)
);

create index idx_category_parent_id on category (parent_id);
