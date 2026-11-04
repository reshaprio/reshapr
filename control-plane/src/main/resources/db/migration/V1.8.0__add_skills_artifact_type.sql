-- Add RESHAPR_SKILLS to artifacts type check constraint.
alter table artifacts drop constraint if exists artifacts_type_check;
alter table artifacts add constraint artifacts_type_check
    check (type in ('JSON_SCHEMA','OPEN_API_SPEC','GRAPHQL_SCHEMA','PROTOBUF_SCHEMA','PROTOBUF_DESCRIPTOR','JSON_FRAGMENT','RESHAPR_PROMPTS','RESHAPR_RESOURCES','RESHAPR_CUSTOM_TOOLS','RESHAPR_TOOLS_OUTPUT_FILTERS','RESHAPR_SKILLS'));
