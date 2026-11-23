-- Add the ASYNC service type and the ASYNC_API_SPEC artifact type (AsyncAPI support).
alter table services drop constraint if exists services_type_check;
alter table services add constraint services_type_check
    check (type in ('REST','GRAPHQL','GRPC','ASYNC'));

alter table artifacts drop constraint if exists artifacts_type_check;
alter table artifacts add constraint artifacts_type_check
    check (type in ('JSON_SCHEMA','OPEN_API_SPEC','GRAPHQL_SCHEMA','PROTOBUF_SCHEMA','PROTOBUF_DESCRIPTOR','ASYNC_API_SPEC','JSON_FRAGMENT','RESHAPR_PROMPTS','RESHAPR_RESOURCES','RESHAPR_CUSTOM_TOOLS','RESHAPR_TOOLS_OUTPUT_FILTERS'));