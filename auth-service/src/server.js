import express from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { randomUUID } from 'node:crypto';
import pg from 'pg';

const { Pool } = pg;
const app = express();
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const port = Number(process.env.PORT || 8081);
const secret = process.env.JWT_SECRET;
if (!secret || secret.length < 32) throw new Error('JWT_SECRET must contain at least 32 characters');
app.use(express.json({ limit: '16kb' }));

app.get('/health', async (_req, res) => {
  try { await pool.query('SELECT 1'); res.json({ status: 'ok', service: 'auth' }); }
  catch { res.status(503).json({ status: 'unavailable' }); }
});

app.post('/auth/register', async (req, res, next) => {
  const email = typeof req.body?.email === 'string' ? req.body.email.trim().toLowerCase() : '';
  const password = req.body?.password;
  const displayName = typeof req.body?.displayName === 'string' ? req.body.displayName.trim() : '';
  if (!/^\S+@\S+\.\S+$/.test(email) || typeof password !== 'string' || password.length < 10 || !displayName || displayName.length > 80) {
    return res.status(400).json({ code: 'INVALID_REGISTRATION', message: 'Provide a valid email, a password of at least 10 characters, and a display name.' });
  }
  try {
    const passwordHash = await bcrypt.hash(password, 12);
    const { rows } = await pool.query(
      'INSERT INTO auth.users(id,email,password_hash,display_name) VALUES($1,$2,$3,$4) RETURNING id,email,display_name,role',
      [randomUUID(), email, passwordHash, displayName]
    );
    res.status(201).json({ user: rows[0] });
  } catch (error) {
    if (error.code === '23505') return res.status(409).json({ code: 'EMAIL_IN_USE' });
    next(error);
  }
});

app.post('/auth/login', async (req, res, next) => {
  const email = typeof req.body?.email === 'string' ? req.body.email.trim().toLowerCase() : '';
  const password = req.body?.password;
  if (!email || typeof password !== 'string') return res.status(400).json({ code: 'INVALID_CREDENTIALS' });
  try {
    const { rows } = await pool.query('SELECT id,email,password_hash,display_name,role FROM auth.users WHERE email=$1', [email]);
    const user = rows[0];
    const matches = await bcrypt.compare(password, user?.password_hash || '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy');
    if (!user || !matches) return res.status(401).json({ code: 'INVALID_CREDENTIALS' });
    const token = jwt.sign({ sub: user.id, role: user.role, name: user.display_name }, secret, { issuer: process.env.JWT_ISSUER || 'stagepass', expiresIn: '1h' });
    res.json({ accessToken: token, tokenType: 'Bearer', expiresIn: 3600, user: { id: user.id, email: user.email, displayName: user.display_name, role: user.role } });
  } catch (error) { next(error); }
});

app.use((error, _req, res, _next) => {
  console.error('auth request failed', error.message);
  res.status(500).json({ code: 'INTERNAL_ERROR' });
});

const server = app.listen(port, () => console.log(`auth service listening on ${port}`));
const shutdown = async () => { server.close(); await pool.end(); };
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
