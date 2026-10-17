import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const initialToken = localStorage.getItem('stagepass.accessToken') || '';
const initialUser = JSON.parse(localStorage.getItem('stagepass.user') || 'null');

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      ...options.headers
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(data?.message || data?.code || `Request failed (${response.status})`);
    error.status = response.status;
    error.data = data;
    throw error;
  }
  return data;
}

function money(cents, currency = 'USD') {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format((cents || 0) / 100);
}

function prettyDate(value) {
  if (!value) return 'Date to be announced';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function App() {
  const [token, setToken] = useState(initialToken);
  const [user, setUser] = useState(initialUser);
  const [shows, setShows] = useState([]);
  const [selectedShow, setSelectedShow] = useState(null);
  const [seats, setSeats] = useState([]);
  const [selectedSeats, setSelectedSeats] = useState([]);
  const [bookings, setBookings] = useState([]);
  const [activeBooking, setActiveBooking] = useState(null);
  const [authMode, setAuthMode] = useState('login');
  const [form, setForm] = useState({ email: 'demo@stagepass.local', password: 'stagepass-demo', displayName: '' });
  const [paymentToken, setPaymentToken] = useState('pm_test_success');
  const [services, setServices] = useState({ auth: false, catalog: false, booking: false });
  const [presence, setPresence] = useState(0);
  const [liveEvents, setLiveEvents] = useState([]);
  const [waitlistInfo, setWaitlistInfo] = useState(null);
  const [waitlistQuantity, setWaitlistQuantity] = useState(1);
  const [loginPrompt, setLoginPrompt] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  const loadShows = useCallback(async () => {
    try {
      const [showData, authHealth, catalogHealth, bookingHealth] = await Promise.all([
        api('/api/catalog/shows'),
        api('/api/auth/health'), api('/api/catalog/health'), api('/api/booking/health')
      ]);
      setShows(showData.shows || []);
      setServices({ auth: authHealth.status === 'ok', catalog: catalogHealth.status === 'ok', booking: bookingHealth.status === 'ok' });
      setNotice(null);
    } catch (error) {
      setServices({ auth: false, catalog: false, booking: false });
      setNotice({ kind: 'error', text: `Could not reach the local services. Is Docker Compose running? ${error.message}` });
    }
  }, []);

  const loadSeats = useCallback(async (showId) => {
    if (!showId) return;
    try {
      setSeats(await api(`/api/booking/shows/${encodeURIComponent(showId)}/seats`));
    } catch (error) {
      setNotice({ kind: 'error', text: error.message });
    }
  }, []);

  const loadBookings = useCallback(async () => {
    if (!token) { setBookings([]); return; }
    try {
      const data = await api('/api/booking/bookings', { token });
      setBookings(data || []);
      if (activeBooking) {
        const refreshed = (data || []).find(item => item.bookingId === activeBooking.bookingId);
        if (refreshed) setActiveBooking(refreshed);
      }
    } catch (error) {
      if (error.status === 401) logout();
      else setNotice({ kind: 'error', text: error.message });
    }
  }, [token, activeBooking]);

  useEffect(() => {
    loadShows();
    const timer = window.setInterval(loadShows, 8000);
    return () => window.clearInterval(timer);
  }, [loadShows]);
  useEffect(() => { if (selectedShow) loadSeats(selectedShow.id); }, [selectedShow, loadSeats]);
  useEffect(() => { setWaitlistInfo(null); setWaitlistQuantity(1); }, [selectedShow?.id, token]);
  useEffect(() => {
    if (!selectedShow) return undefined;
    let alive = true;
    const showId = encodeURIComponent(selectedShow.id);
    const refreshPresence = async () => {
      try {
        if (token) await api(`/api/booking/shows/${showId}/presence`, { method: 'POST', token });
        const data = await api(`/api/booking/shows/${showId}/presence`);
        if (alive) setPresence(data.activeViewers || 0);
      } catch { /* Show page remains usable if live presence is unavailable. */ }
    };
    setLiveEvents([]);
    refreshPresence();
    const timer = window.setInterval(refreshPresence, 10000);
    const source = new EventSource(`/api/booking/shows/${showId}/events`);
    const eventTypes = ['seat.held', 'seat.booked', 'booking.confirmed', 'booking.cancelled', 'booking.expired', 'seat.released', 'waitlist.offered', 'waitlist.offer_expired', 'presence.count_updated'];
    const handlers = eventTypes.map(type => {
      const handler = event => {
        let payload = {};
        try { payload = JSON.parse(event.data); } catch { /* Keep the event name if payload is malformed. */ }
        const details = payload.data || {};
        setLiveEvents(current => [{ id: `${Date.now()}-${Math.random()}`, type, details }, ...current].slice(0, 8));
        if (type.startsWith('seat.') || type.startsWith('booking.') || type === 'waitlist.offered' || type === 'waitlist.offer_expired') loadSeats(selectedShow.id);
      };
      source.addEventListener(type, handler);
      return [type, handler];
    });
    return () => { alive = false; window.clearInterval(timer); handlers.forEach(([type, handler]) => source.removeEventListener(type, handler)); source.close(); };
  }, [selectedShow, token, loadSeats]);
  useEffect(() => { loadBookings(); }, [token]);
  useEffect(() => {
    if (!selectedShow || !token) { setWaitlistInfo(null); return; }
    let alive = true;
    const refresh = async () => {
      try {
        const data = await api(`/api/booking/shows/${encodeURIComponent(selectedShow.id)}/waitlist/me`, { token });
        if (alive) setWaitlistInfo(data);
      } catch (error) { if (alive && error.status === 404) setWaitlistInfo(null); }
    };
    refresh();
    const timer = window.setInterval(refresh, 2000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [selectedShow, token]);
  useEffect(() => {
    if (!token) return undefined;
    const timer = window.setInterval(loadBookings, 2500);
    return () => window.clearInterval(timer);
  }, [token, loadBookings]);

  function logout() {
    localStorage.removeItem('stagepass.accessToken');
    localStorage.removeItem('stagepass.user');
    setToken(''); setUser(null); setBookings([]); setActiveBooking(null);
    setNotice({ kind: 'success', text: 'You have been signed out.' });
  }

  async function submitAuth(event) {
    event.preventDefault(); setBusy(true); setNotice(null);
    try {
      const path = authMode === 'login' ? '/api/auth/login' : '/api/auth/register';
      if (authMode === 'register') {
        await api(path, { method: 'POST', body: form });
        setAuthMode('login');
        setNotice({ kind: 'success', text: 'Account created. Sign in with your new email and password.' });
      } else {
        const data = await api(path, { method: 'POST', body: { email: form.email, password: form.password } });
        localStorage.setItem('stagepass.accessToken', data.accessToken);
        localStorage.setItem('stagepass.user', JSON.stringify(data.user));
        setToken(data.accessToken); setUser(data.user);
        setNotice({ kind: 'success', text: `Signed in as ${data.user.displayName}.` });
      }
    } catch (error) { setNotice({ kind: 'error', text: error.message }); }
    finally { setBusy(false); }
  }

  async function holdSeats() {
    if (!token) { setLoginPrompt(true); return; }
    if (!selectedShow || selectedSeats.length === 0) return;
    setBusy(true); setNotice(null);
    try {
      const result = await api(`/api/booking/shows/${selectedShow.id}/holds`, {
        method: 'POST', token, body: { seatIds: selectedSeats }
      });
      setActiveBooking(result); setSelectedSeats([]);
      setNotice({ kind: 'success', text: `Seats held for 5 minutes. Booking ${result.bookingId.slice(0, 8)}…` });
      await Promise.all([loadSeats(selectedShow.id), loadBookings()]);
    } catch (error) { setNotice({ kind: 'error', text: error.message }); await loadSeats(selectedShow.id); }
    finally { setBusy(false); }
  }

  async function extendBooking(bookingId) {
    setBusy(true);
    try {
      const result = await api(`/api/booking/bookings/${bookingId}/extend`, { method: 'PUT', token });
      setNotice({ kind: 'success', text: `Hold extended until ${prettyDate(result.expiresAt)}.` });
      await loadBookings(); if (selectedShow) await loadSeats(selectedShow.id);
    } catch (error) { setNotice({ kind: 'error', text: error.message }); }
    finally { setBusy(false); }
  }

  async function confirmBooking(booking) {
    setBusy(true); setNotice(null);
    try {
      const result = await api(`/api/booking/bookings/${booking.bookingId}/confirm`, {
        method: 'POST', token,
        headers: { 'Idempotency-Key': `web-${crypto.randomUUID()}` },
        body: { paymentMethodToken: paymentToken }
      });
      setNotice({ kind: result.status === 'CONFIRMED' ? 'success' : 'info', text: `Payment result: ${result.status}.` });
      await loadBookings(); if (selectedShow) await loadSeats(selectedShow.id);
    } catch (error) {
      const detail = error.data?.failureCode || error.message;
      setNotice({ kind: 'error', text: `Payment/booking response: ${error.data?.status || 'failed'} — ${detail}. You can retry a declined payment with a new attempt.` });
      await loadBookings(); if (selectedShow) await loadSeats(selectedShow.id);
    } finally { setBusy(false); }
  }

  async function cancelBooking(booking) {
    setBusy(true);
    try {
      const result = await api(`/api/booking/bookings/${booking.bookingId}/cancel`, { method: 'POST', token });
      setNotice({ kind: 'success', text: `Booking ${result.status.toLowerCase()}.` });
      await loadBookings(); if (selectedShow) await loadSeats(selectedShow.id);
    } catch (error) { setNotice({ kind: 'error', text: error.message }); }
    finally { setBusy(false); }
  }

  async function joinWaitlist() {
    if (!token) { setLoginPrompt(true); return; }
    if (!selectedShow) return;
    setBusy(true);
    try {
      const data = await api(`/api/booking/shows/${encodeURIComponent(selectedShow.id)}/waitlist`, { method: 'POST', token, body: { quantity: waitlistQuantity } });
      setWaitlistInfo(data);
      setNotice({ kind: 'success', text: data.offeredSeatIds?.length ? `Your offer is ready for ${data.offeredSeatIds.join(', ')}.` : `Joined the show waitlist for ${waitlistQuantity} seat${waitlistQuantity === 1 ? '' : 's'}. You are number ${data.position}.` });
    } catch (error) { setNotice({ kind: 'error', text: error.message }); }
    finally { setBusy(false); }
  }

  async function acceptWaitlistOffer() {
    setBusy(true);
    try {
      const result = await api(`/api/booking/shows/${encodeURIComponent(selectedShow.id)}/waitlist/accept`, { method: 'POST', token });
      setWaitlistInfo(null);
      setActiveBooking(result);
      setNotice({ kind: 'success', text: `Offer accepted. Seats ${result.seatIds.join(', ')} are held for five minutes; complete payment in Bookings.` });
      await Promise.all([loadSeats(selectedShow.id), loadBookings()]);
    } catch (error) { setNotice({ kind: 'error', text: error.message }); }
    finally { setBusy(false); }
  }

  const groupedSeats = useMemo(() => {
    const groups = new Map();
    for (const seat of seats) {
      const key = seat.sectionId;
      if (!groups.has(key)) groups.set(key, { name: seat.sectionName, price: seat.priceCents, rows: new Map() });
      const group = groups.get(key);
      if (!group.rows.has(seat.row)) group.rows.set(seat.row, []);
      group.rows.get(seat.row).push(seat);
    }
    return [...groups.entries()];
  }, [seats]);

  function toggleSeat(seat) {
    if (seat.status !== 'AVAILABLE') return;
    setSelectedSeats(current => current.includes(seat.seatId)
      ? current.filter(id => id !== seat.seatId)
      : current.length < 8 ? [...current, seat.seatId] : current);
  }

  const selectedTotal = seats.filter(seat => selectedSeats.includes(seat.seatId)).reduce((sum, seat) => sum + seat.priceCents, 0);
  const selectedCurrency = selectedShow?.currency || 'USD';
  const activeStates = new Set(['HELD', 'PAYMENT_FAILED', 'PAYMENT_PENDING']);

  return <div className="app-shell">
    <header className="topbar">
      <a className="brand" href="#top" aria-label="StagePass home"><span className="brand-mark">S</span><span>stagepass<span className="brand-dot">.</span></span></a>
      <div className="service-strip" aria-label="Service health">
        {Object.entries(services).map(([name, healthy]) => <span className="service-pill" key={name}><i className={healthy ? 'online' : ''} />{name}</span>)}
      </div>
      <div className="account-area">{user ? <><span className="welcome">Hi, {user.displayName}</span><button className="quiet-button" onClick={logout}>Sign out</button></> : <span className="welcome">Local booking demo</span>}</div>
    </header>

    <main id="top">
      <section className="hero">
        <div className="hero-copy"><div className="eyebrow"><span className="live-dot" /> LOCAL SHOWS · LIVE INVENTORY</div><h1>Find your<br /><em>next live</em> moment.</h1><p>Browse the lineup, pick your seats, and follow a booking all the way through payment.</p><a className="hero-link" href="#shows">Explore shows <span>↓</span></a></div>
        <div className="hero-art" aria-hidden="true"><div className="art-orbit orbit-one" /><div className="art-orbit orbit-two" /><div className="art-ticket"><span className="ticket-kicker">ADMIT ONE</span><span className="ticket-star">✳</span><span className="ticket-name">LIVE<br />IS BETTER</span><span className="ticket-line" /><span className="ticket-foot">STAGEPASS · 2026</span></div><span className="art-note note-top">SOUND<br />IN MOTION</span><span className="art-note note-bottom">YOUR NIGHT<br />STARTS HERE</span></div>
      </section>

      {notice && <div className={`notice ${notice.kind}`} role="status"><span>{notice.kind === 'error' ? '!' : '✳'}</span>{notice.text}<button onClick={() => setNotice(null)} aria-label="Dismiss message">×</button></div>}

      <section className="content-grid" id="shows">
        <div className="main-column">
          <div className="section-heading"><div><span className="eyebrow muted">THE LINEUP</span><h2>Shows worth showing up for</h2></div><span className="count-label">{shows.length} events</span></div>
          {shows.length === 0 ? <div className="empty-state">Loading seeded shows…</div> : <div className="show-grid">{shows.map((show, index) => <button className={`show-card ${selectedShow?.id === show.id ? 'chosen' : ''}`} key={show.id} onClick={() => { setSelectedShow(show); setSelectedSeats([]); }}>
            <div className={`show-art art-${index % 3}`}><span className="show-art-label">{show.status === 'on_sale' ? 'ON SALE' : show.status?.replace('_', ' ')}</span><span className="show-art-index">0{index + 1}</span><span className="show-graphic">{['✳', '◉', '✦'][index % 3]}</span><span className="show-art-title">{show.artist}</span></div>
            <div className="show-card-info"><div><h3>{show.title}</h3><p>{show.venue_name} · {show.city}</p></div><span className="arrow-chip">↗</span></div>
            <div className="show-card-foot"><span>{prettyDate(show.starts_at)}</span><span>{show.seat_count} seats</span></div>
          </button>)}</div>}

          {selectedShow && <section className="seat-panel" aria-labelledby="seat-title">
            <div className="section-heading seat-heading"><div><span className="eyebrow muted">PICK YOUR SPOT</span><h2 id="seat-title">{selectedShow.title}</h2><p>{selectedShow.venue_name} · {prettyDate(selectedShow.starts_at)}</p></div><button className="quiet-button" onClick={() => loadSeats(selectedShow.id)}>↻ Refresh seats</button></div>
            <div className="seat-legend"><span><i className="legend-dot available" /> Available</span><span><i className="legend-dot selected" /> Selected</span><span><i className="legend-dot held" /> Held</span><span><i className="legend-dot confirmed" /> Booked</span></div>
            <div className="stage-label">STAGE</div>
            <div className="seat-sections">{groupedSeats.map(([sectionId, section]) => <div className="seat-section" key={sectionId}><div className="section-label"><span>{section.name}</span><small>from {money(section.price, selectedCurrency)}</small></div>{[...section.rows.entries()].map(([row, rowSeats]) => <div className="seat-row" key={row}><span className="row-label">{row}</span><div className="seat-row-buttons">{rowSeats.map(seat => {
              const chosen = selectedSeats.includes(seat.seatId);
              return <button key={seat.seatId} title={`${seat.seatId} · ${seat.status}`} aria-label={`${section.name}, row ${seat.row}, seat ${seat.seatNumber}, ${chosen ? 'selected' : seat.status.toLowerCase()}`} disabled={seat.status !== 'AVAILABLE'} className={`seat ${seat.status.toLowerCase()} ${chosen ? 'selected' : ''}`} onClick={() => toggleSeat(seat)}>{seat.seatNumber}</button>;
            })}</div><span className="row-label">{row}</span></div>)}</div>)}</div>
            <div className="seat-actions"><div><strong>{selectedSeats.length} selected</strong><span>{selectedSeats.length ? money(selectedTotal, selectedCurrency) : 'Choose up to 8 seats'}</span></div><button className="primary-button" onClick={holdSeats} disabled={busy || !selectedSeats.length}>{busy ? 'Working…' : 'Hold selected seats'} <span>→</span></button></div>
            <section className="realtime-panel" aria-label="Live show features">
              <div className="realtime-heading"><div><span className="eyebrow muted">PHASE 5 · LIVE SHOW</span><h3>Audience & waitlist</h3></div><span className="viewer-count"><i />{presence} viewing now</span></div>
              {!user ? <p className="realtime-hint">Sign in to join the waitlist. Presence updates automatically while this show is open.</p> : <>
                <div className="waitlist-join"><label htmlFor="waitlist-quantity">Number of seats</label><select id="waitlist-quantity" value={waitlistQuantity} onChange={event => setWaitlistQuantity(Number(event.target.value))}>{Array.from({ length: 8 }, (_, index) => <option key={index + 1} value={index + 1}>{index + 1} seat{index ? 's' : ''}</option>)}</select><button className="primary-button small" disabled={busy} onClick={joinWaitlist}>{waitlistInfo ? 'Update my request' : 'Join waitlist'} →</button></div>
                {waitlistInfo && <div className="waitlist-list"><div className="waitlist-item"><div><strong>{waitlistInfo.offeredSeatIds?.length ? `Your seats are ready: ${waitlistInfo.offeredSeatIds.join(', ')}` : `You’re in the show-wide queue`}</strong><span>{waitlistInfo.offeredSeatIds?.length ? `Offer expires ${new Date(waitlistInfo.offerExpiresAtEpochMillis).toLocaleTimeString()}` : `Position ${waitlistInfo.position} · approximate wait ${Math.ceil((waitlistInfo.estimatedWaitSeconds || 0) / 60)} min · ${waitlistInfo.quantity} seat${waitlistInfo.quantity === 1 ? '' : 's'}`}</span></div>{waitlistInfo.offeredSeatIds?.length > 0 && <button className="primary-button small" disabled={busy} onClick={acceptWaitlistOffer}>Accept offer →</button>}</div></div>}
              </>}
              <div className="live-feed"><strong>Live updates</strong>{liveEvents.length === 0 ? <span className="realtime-hint">Waiting for show activity…</span> : liveEvents.map(event => <div className="live-event" key={event.id}><i /> <span>{event.type.replaceAll('.', ' ')}{event.details.seatIds?.length ? ` · seats ${event.details.seatIds.join(', ')}` : event.details.seatId ? ` · seat ${event.details.seatId}` : ''}</span><small>{new Date(Number(event.id.split('-')[0])).toLocaleTimeString()}</small></div>)}</div>
            </section>
          </section>}

          {user && <section className="booking-panel"><div className="section-heading"><div><span className="eyebrow muted">YOUR ACTIVITY</span><h2>Bookings</h2></div><button className="quiet-button" onClick={loadBookings}>↻ Refresh</button></div>
            {bookings.length === 0 ? <div className="empty-state compact">Your bookings will appear here.</div> : <div className="booking-list">{bookings.map(booking => <article className="booking-row" key={booking.bookingId}><div className="booking-main"><span className={`status-badge state-${booking.status.toLowerCase()}`}>{booking.status.replace('_', ' ')}</span><strong>{shows.find(show => show.id === booking.showId)?.title || booking.showId}</strong><span>{booking.seatIds.join(', ')}</span><small>Booking {booking.bookingId}</small></div><div className="booking-actions">{activeStates.has(booking.status) && <><label className="payment-select-label">Mock payment<select value={paymentToken} onChange={event => setPaymentToken(event.target.value)}><option value="pm_test_success">Success</option><option value="pm_test_decline">Decline</option><option value="pm_test_late_success">Late success</option></select></label><button className="primary-button small" disabled={busy} onClick={() => confirmBooking(booking)}>Pay / retry</button><button className="quiet-button" disabled={busy} onClick={() => extendBooking(booking.bookingId)}>Extend hold</button><button className="quiet-button danger-text" disabled={busy} onClick={() => cancelBooking(booking)}>Cancel</button></>}</div></article>)}</div>}
          </section>}
        </div>

        <aside className="side-column">
          {!user ? <section className="auth-card" id="account-access"><div className="auth-top"><span className="eyebrow">YOUR NIGHT OUT</span><span className="auth-icon">↗</span></div><h2>{authMode === 'login' ? 'Get in the door.' : 'Join the list.'}</h2><p>{authMode === 'login' ? 'Sign in to hold seats and manage your bookings.' : 'Create an account to start booking.'}</p><form onSubmit={submitAuth}>
            {authMode === 'register' && <label>Display name<input required maxLength="80" autoComplete="name" value={form.displayName} onChange={event => setForm({ ...form, displayName: event.target.value })} placeholder="Your name" /></label>}
            <label>Email<input type="email" required autoComplete="email" value={form.email} onChange={event => setForm({ ...form, email: event.target.value })} /></label>
            <label>Password<input type="password" required minLength={authMode === 'register' ? 10 : 1} autoComplete={authMode === 'login' ? 'current-password' : 'new-password'} value={form.password} onChange={event => setForm({ ...form, password: event.target.value })} /></label>
            <button className="primary-button auth-submit" disabled={busy}>{busy ? 'Please wait…' : authMode === 'login' ? 'Sign in' : 'Create account'} <span>→</span></button>
          </form><button className="text-button" onClick={() => setAuthMode(authMode === 'login' ? 'register' : 'login')}>{authMode === 'login' ? 'New here? Create an account' : 'Already have an account? Sign in'}</button><div className="demo-hint"><span>DEMO LOGIN</span><code>demo@stagepass.local</code><code>stagepass-demo</code></div></section> : <section className="profile-card"><div className="profile-avatar">{(user.displayName || 'S').slice(0, 1).toUpperCase()}</div><span className="eyebrow">SIGNED IN AS</span><h2>{user.displayName}</h2><p>{user.email}</p><div className="profile-divider" /><div className="profile-stat"><span>My bookings</span><strong>{bookings.length}</strong></div><div className="profile-stat"><span>Confirmed</span><strong>{bookings.filter(item => item.status === 'CONFIRMED').length}</strong></div></section>}

          <section className="how-card"><span className="eyebrow muted">HOW IT WORKS</span><div className="how-step"><span>01</span><div><strong>Pick your seats</strong><p>Available seats are shared across all three booking nodes.</p></div></div><div className="how-step"><span>02</span><div><strong>Lock in your hold</strong><p>A Redis-backed hold gives you five minutes to check out.</p></div></div><div className="how-step"><span>03</span><div><strong>Try mock checkout</strong><p>Use success, decline, or delayed payment to explore the flow.</p></div></div><div className="architecture-note"><span>✳</span><p>Every action here calls the running StagePass APIs. No sample bookings are fabricated.</p></div></section>
        </aside>
      </section>
    </main>

    {loginPrompt && <div className="modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setLoginPrompt(false); }}><section className="login-prompt" role="dialog" aria-modal="true" aria-labelledby="login-prompt-title"><button className="modal-close" onClick={() => setLoginPrompt(false)} aria-label="Close">×</button><span className="eyebrow">ONE QUICK STEP</span><h2 id="login-prompt-title">Sign in to hold seats</h2><p>Your seat selection is still here. Sign in or create an account to place the hold.</p><div className="prompt-actions"><button className="primary-button" onClick={() => { setAuthMode('login'); setLoginPrompt(false); document.getElementById('account-access')?.scrollIntoView({ behavior: 'smooth', block: 'center' }); }}>Sign in →</button><button className="quiet-button" onClick={() => { setAuthMode('register'); setLoginPrompt(false); document.getElementById('account-access')?.scrollIntoView({ behavior: 'smooth', block: 'center' }); }}>Create account</button></div></section></div>}
    <footer><span>STAGEPASS · LOCAL DEVELOPMENT</span><span>Built to make live moments happen.</span></footer>
  </div>;
}

createRoot(document.getElementById('root')).render(<React.StrictMode><App /></React.StrictMode>);
