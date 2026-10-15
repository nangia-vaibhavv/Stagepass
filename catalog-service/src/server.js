import express from 'express';
import pg from 'pg';

const { Pool } = pg;
const app = express();
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const port = Number(process.env.PORT || 8082);

app.get('/health', async (_req, res) => {
  try { await pool.query('SELECT 1'); res.json({ status: 'ok', service: 'catalog' }); }
  catch { res.status(503).json({ status: 'unavailable' }); }
});

app.get('/shows', async (req, res, next) => {
  try {
    const limit = Math.min(Math.max(Number.parseInt(req.query.limit, 10) || 20, 1), 100);
    const cursor = typeof req.query.cursor === 'string' ? req.query.cursor : null;
    const { rows } = await pool.query(
      `SELECT s.id,s.title,s.artist,s.starts_at,s.sale_starts_at,s.status,s.currency,
              v.id AS venue_id,v.name AS venue_name,v.city,
              count(DISTINCT seat.id)::int AS seat_count
       FROM catalog.shows s JOIN catalog.venues v ON v.id=s.venue_id
       LEFT JOIN catalog.sections sec ON sec.venue_id=v.id
       LEFT JOIN catalog.seats seat ON seat.section_id=sec.id
       WHERE ($1::text IS NULL OR s.id>$1) AND ($2::text IS NULL OR s.status=$2)
       GROUP BY s.id,v.id ORDER BY s.id LIMIT $3`,
      [cursor, typeof req.query.status === 'string' ? req.query.status : null, limit + 1]
    );
    const hasMore = rows.length > limit;
    const shows = rows.slice(0, limit);
    res.json({ shows, nextCursor: hasMore ? shows.at(-1).id : null });
  } catch (error) { next(error); }
});

app.get('/shows/:showId', async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT s.id,s.title,s.artist,s.starts_at,s.sale_starts_at,s.status,s.currency,
              v.id AS venue_id,v.name AS venue_name,v.city,v.address
       FROM catalog.shows s JOIN catalog.venues v ON v.id=s.venue_id WHERE s.id=$1`, [req.params.showId]
    );
    if (!rows[0]) return res.status(404).json({ code: 'SHOW_NOT_FOUND' });
    res.json({ show: rows[0] });
  } catch (error) { next(error); }
});

app.get('/shows/:showId/seats', async (req, res, next) => {
  try {
    const { rows: showRows } = await pool.query('SELECT venue_id FROM catalog.shows WHERE id=$1', [req.params.showId]);
    if (!showRows[0]) return res.status(404).json({ code: 'SHOW_NOT_FOUND' });
    const { rows } = await pool.query(
      `SELECT sec.id AS section_id,sec.name AS section_name,sec.price_cents,
              seat.id AS seat_id,seat.row_label,seat.seat_number
       FROM catalog.sections sec JOIN catalog.seats seat ON seat.section_id=sec.id
       WHERE sec.venue_id=$1 ORDER BY sec.id,seat.row_label,seat.seat_number`, [showRows[0].venue_id]
    );
    res.json({ showId: req.params.showId, sections: rows });
  } catch (error) { next(error); }
});

app.use((error, _req, res, _next) => {
  console.error('catalog request failed', error.message);
  res.status(500).json({ code: 'INTERNAL_ERROR' });
});

const server = app.listen(port, () => console.log(`catalog service listening on ${port}`));
const shutdown = async () => { server.close(); await pool.end(); };
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
