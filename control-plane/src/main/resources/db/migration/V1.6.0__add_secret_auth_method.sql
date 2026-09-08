-- Flyway migration V1.6.0
-- Add an explicit authentication method discriminator to secrets.
-- Historically the auth mechanism was inferred from which fields happen to be populated
-- (token vs username/password) plus the use_elicitation flag. This column makes the
-- mechanism explicit so the proxy can dispatch on it and so new methods (e.g. OAuth2
-- Client Credentials) can be added without relying on implicit inference.
-- No CHECK constraint is added on purpose: new SecretAuthMethod values must not require
-- a schema migration.

ALTER TABLE secrets
    ADD COLUMN IF NOT EXISTS auth_method varchar(64);

-- Backfill existing rows by inferring the method (order matters: most specific first).

-- Interactive OAuth2 (elicitation + OAuth2 client configuration present).
UPDATE secrets
SET auth_method = 'OAUTH2_AUTHORIZATION_CODE'
WHERE auth_method IS NULL
  AND use_elicitation = true
  AND oauth2_client_configuration IS NOT NULL;

-- Elicited secrets without OAuth2 config are manual token entry -> Bearer token.
UPDATE secrets
SET auth_method = 'BEARER_TOKEN'
WHERE auth_method IS NULL
  AND use_elicitation = true;

-- Static token secrets.
UPDATE secrets
SET auth_method = 'BEARER_TOKEN'
WHERE auth_method IS NULL
  AND token IS NOT NULL;

-- Static username/password secrets.
UPDATE secrets
SET auth_method = 'BASIC'
WHERE auth_method IS NULL
  AND username IS NOT NULL
  AND password IS NOT NULL;

