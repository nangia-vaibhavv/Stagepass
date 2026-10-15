CREATE TABLE auth.users (
  id uuid PRIMARY KEY,
  email text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  display_name text NOT NULL,
  role text NOT NULL DEFAULT 'user' CHECK (role IN ('user','admin')),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO auth.users (id,email,password_hash,display_name,role) VALUES
  ('00000000-0000-4000-8000-000000000001','demo@stagepass.local',crypt('stagepass-demo',gen_salt('bf',12)),'StagePass Demo','admin')
ON CONFLICT (email) DO NOTHING;
