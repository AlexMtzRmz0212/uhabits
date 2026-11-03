create table if not exists SyncMeta (
    key text primary key not null,
    hlc text not null,
    deleted integer not null default 0
);
create table if not exists SyncState (
    name text primary key not null,
    value text not null
);
