-- Sign-in and saved lists were dropped (ADR 0043), so the empty schema V1 made for them goes too.
-- RESTRICT (the default, written out) makes this fail rather than delete anything that was put there.
DROP SCHEMA IF EXISTS app RESTRICT;
