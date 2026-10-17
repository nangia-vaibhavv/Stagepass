-- The demo admin is useful for local development, but its credentials are public.
-- Production Compose sets DISABLE_DEMO_ADMIN=true before the first migration.
DO $$
BEGIN
  IF '${disableDemoAdmin}'::boolean THEN
    DELETE FROM auth.users
    WHERE email = 'demo@stagepass.local'
      AND role = 'admin';
  END IF;
END $$;
