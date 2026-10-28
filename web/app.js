// Phone remote for Sports Command Center.
// Builds the same watchlist JSON the TV stores, and sends it through the ntfy.sh relay to the
// TV's channel. Keep LEAGUES / STATS in sync with core/src/main/kotlin/com/sportscc/core/{League,StatCatalog}.kt.
(function () {
  'use strict';

  const ESPN = 'https://site.api.espn.com/apis/site/v2/sports';
  const RELAY = 'https://ntfy.sh';
  const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

  const LEAGUES = [
    { id: 'NFL', sport: 'football', path: 'nfl', label: 'NFL', kind: 'football' },
    { id: 'NCAAF', sport: 'football', path: 'college-football', label: 'College Football', kind: 'football' },
    { id: 'NBA', sport: 'basketball', path: 'nba', label: 'NBA', kind: 'basketball' },
    { id: 'WNBA', sport: 'basketball', path: 'wnba', label: 'WNBA', kind: 'basketball' },
    { id: 'NCAAM', sport: 'basketball', path: 'mens-college-basketball', label: 'College Basketball', kind: 'basketball' },
    { id: 'MLB', sport: 'baseball', path: 'mlb', label: 'MLB', kind: 'baseball' },
    { id: 'NHL', sport: 'hockey', path: 'nhl', label: 'NHL', kind: 'hockey' },
    { id: 'MLS', sport: 'soccer', path: 'usa.1', label: 'MLS', kind: 'soccer' },
    { id: 'EPL', sport: 'soccer', path: 'eng.1', label: 'Premier League', kind: 'soccer' },
  ];
  const STATS = {
    football: [
      ['pass_yds', 'Passing Yards', 'Pass Yds'], ['pass_td', 'Passing TDs', 'Pass TD'], ['pass_cmp', 'Completions', 'Cmp'],
      ['pass_int', 'Interceptions Thrown', 'INT'], ['rush_yds', 'Rushing Yards', 'Rush Yds'], ['rush_att', 'Rush Attempts', 'Carries'],
      ['rush_td', 'Rushing TDs', 'Rush TD'], ['rec', 'Receptions', 'Rec'], ['rec_yds', 'Receiving Yards', 'Rec Yds'],
      ['rec_td', 'Receiving TDs', 'Rec TD'], ['rush_rec_yds', 'Rush + Rec Yards', 'Rush+Rec'], ['any_td', 'Anytime TD (rush/rec)', 'TDs'],
      ['fpts_ppr', 'Fantasy Points (PPR)', 'PPR'], ['fpts_half', 'Fantasy Points (Half PPR)', 'Half PPR'],
    ],
    basketball: [
      ['pts', 'Points', 'PTS'], ['reb', 'Rebounds', 'REB'], ['ast', 'Assists', 'AST'], ['threes', '3-Pointers Made', '3PM'],
      ['stl', 'Steals', 'STL'], ['blk', 'Blocks', 'BLK'], ['pra', 'Pts + Reb + Ast', 'PRA'], ['pr', 'Pts + Reb', 'P+R'],
      ['pa', 'Pts + Ast', 'P+A'], ['fpts_dk', 'Fantasy Points (DK)', 'FPTS'],
    ],
    baseball: [
      ['hits', 'Hits', 'H'], ['hr', 'Home Runs', 'HR'], ['rbi', 'RBIs', 'RBI'], ['runs', 'Runs', 'R'],
      ['hrr', 'Hits + Runs + RBIs', 'H+R+RBI'], ['walks', 'Walks', 'BB'], ['p_k', 'Pitcher Strikeouts', 'K'],
      ['p_er', 'Earned Runs Allowed', 'ER'], ['p_h', 'Hits Allowed', 'H Allowed'], ['p_ip', 'Innings Pitched', 'IP'],
    ],
    hockey: [
      ['goals', 'Goals', 'G'], ['assists', 'Assists', 'A'], ['points', 'Points', 'PTS'], ['sog', 'Shots on Goal', 'SOG'],
      ['saves', 'Saves (goalie)', 'SV'],
    ],
    soccer: [],
  };
  const leagueById = id => LEAGUES.find(l => l.id === id);
  const statsFor = leagueId => STATS[leagueById(leagueId).kind];
  const statShort = (leagueId, statId) => (statsFor(leagueId).find(s => s[0] === statId) || [, , statId])[2];
  const keyOf = it => it.type === 'team' ? `team:${it.league}:${it.teamId}` : `player:${it.league}:${it.athleteId}`;
  const num = n => (Math.round(n * 10) / 10).toString();
  const $ = id => document.getElementById(id);

  // ---------- storage (this phone only) ----------
  const store = {
    get(k, d) { try { const v = localStorage.getItem('scc.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('scc.' + k, JSON.stringify(v)); } catch (e) { /* private mode: keep in memory */ } },
  };
  let items = store.get('items', []);
  let code = store.get('code', '');
  let dirty = store.get('dirty', false);
  let lastSent = store.get('lastSent', 0);

  function save() { store.set('items', items); store.set('dirty', dirty); }
  function changed() { dirty = true; save(); render(); }

  // ---------- wire format (matches SyncCodec in core) ----------
  async function encode(payload) {
    const json = JSON.stringify(payload);
    if (!('CompressionStream' in window)) return json;
    const stream = new Blob([new TextEncoder().encode(json)]).stream().pipeThrough(new CompressionStream('gzip'));
    const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return 'z1:' + btoa(bin);
  }
  async function decode(text) {
    if (!text.startsWith('z1:')) return JSON.parse(text);
    const bin = atob(text.slice(3));
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
    return JSON.parse(await new Response(stream).text());
  }
  const normalize = c => c.toUpperCase().split('').filter(ch => CODE_ALPHABET.includes(ch)).join('');
  const topicIn = c => `scc-tv-${normalize(c).toLowerCase()}-in`;
  const topicTv = c => `scc-tv-${normalize(c).toLowerCase()}-tv`;
  const pretty = c => { const n = normalize(c); return n.slice(0, 4) + '-' + n.slice(4); };

  // ---------- ESPN ----------
  const cache = {};
  async function espn(league, path) {
    const url = `${ESPN}/${league.sport}/${league.path}/${path}`;
    if (!cache[url]) {
      cache[url] = fetch(url).then(r => { if (!r.ok) throw new Error('ESPN returned ' + r.status); return r.json(); });
      cache[url].catch(() => delete cache[url]);
    }
    return cache[url];
  }
  async function teams(league) {
    const j = await espn(league, 'teams?limit=1000');
    const list = ((((j.sports || [])[0] || {}).leagues || [])[0] || {}).teams || [];
    return list.map(w => w.team || w).filter(t => t && t.id).map(t => ({
      id: String(t.id), abbreviation: t.abbreviation || '', name: t.displayName || t.name || '',
      short: t.shortDisplayName || t.name || '', logo: ((t.logos || [])[0] || {}).href || null, color: t.color || null,
    })).sort((a, b) => a.name.localeCompare(b.name));
  }
  async function roster(league, teamId) {
    const j = await espn(league, `teams/${teamId}/roster`);
    const flat = (j.athletes || []).flatMap(a => Array.isArray(a.items) ? a.items : [a]);
    return flat.filter(a => a && a.id).map(a => ({
      id: String(a.id), name: a.displayName || a.fullName || '', position: (a.position || {}).abbreviation || null,
      jersey: a.jersey || null, headshot: (a.headshot || {}).href || null,
    }));
  }

  // ---------- render list ----------
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
  function pic(src, isLogo) {
    return src ? `<img class="pic${isLogo ? ' logo' : ''}" src="${esc(src)}" alt="" loading="lazy">` : `<div class="pic"></div>`;
  }
  function render() {
    $('list').innerHTML = items.map((it, i) => {
      const league = leagueById(it.league);
      if (it.type === 'team') {
        return `<div class="item"><div class="item-head">${pic(it.logo, true)}
          <div class="who"><div class="name">${esc(it.name)}</div><div class="sub"><span class="league">${esc(league.label)}</span> Team · scores &amp; scoring alerts</div></div>
          <button class="x" data-remove="${i}" aria-label="Remove ${esc(it.name)}">×</button></div></div>`;
      }
      const props = (it.props || []).map((p, j) =>
        `<span class="prop${p.side === 'UNDER' ? ' under' : ''}"><b>${p.side === 'UNDER' ? 'U' : 'O'} ${num(p.line)}</b> ${esc(statShort(it.league, p.statId))}
          <button class="x" data-unprop="${i}:${j}" aria-label="Remove prop">×</button></span>`).join('');
      return `<div class="item"><div class="item-head">${pic(it.headshot)}
          <div class="who"><div class="name">${esc(it.name)}</div><div class="sub"><span class="league">${esc(league.label)}</span> ${esc([it.teamAbbreviation, it.position, it.jersey ? '#' + it.jersey : null].filter(Boolean).join(' · '))}</div></div>
          <button class="x" data-remove="${i}" aria-label="Remove ${esc(it.name)}">×</button></div>
        <div class="props">${props}<button class="addprop" data-addprop="${i}" type="button">+ Prop</button></div></div>`;
    }).join('');
    renderStatus();
  }
  function renderStatus(msg, cls) {
    const el = $('sendStatus');
    el.className = 'sendstatus' + (cls ? ' ' + cls : dirty ? ' dirty' : lastSent ? ' ok' : '');
    el.textContent = msg || (!code ? 'Link your TV to send' : dirty ? 'Changes not sent yet'
      : lastSent ? 'Sent ' + new Date(lastSent).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) + '. The TV updates within about 20 s.' : 'Not sent yet');
    $('sendBtn').disabled = !code;
  }

  $('list').addEventListener('click', e => {
    const t = e.target.closest('button'); if (!t) return;
    if (t.dataset.remove) { items.splice(+t.dataset.remove, 1); changed(); }
    else if (t.dataset.unprop) { const [i, j] = t.dataset.unprop.split(':').map(Number); items[i].props.splice(j, 1); changed(); }
    else if (t.dataset.addprop) openProp(+t.dataset.addprop);
  });

  // ---------- linking ----------
  function renderLink() {
    $('linkForm').hidden = !!code;
    $('linked').hidden = !code;
    if (code) $('codeDisplay').textContent = pretty(code);
    renderStatus();
  }
  function link(c) {
    const n = normalize(c);
    if (n.length !== 8) { $('codeError').textContent = 'The code is 8 letters and numbers, like ABCD-EFGH.'; $('codeError').hidden = false; return false; }
    $('codeError').hidden = true;
    code = n; store.set('code', code); renderLink();
    if (!items.length) loadFromTv();
    return true;
  }
  $('codeForm').addEventListener('submit', e => { e.preventDefault(); link($('codeInput').value); });
  $('unlinkBtn').addEventListener('click', () => { code = ''; store.set('code', ''); $('codeInput').value = ''; renderLink(); });

  async function loadFromTv() {
    const s = $('loadStatus'); s.hidden = false; s.textContent = 'Looking for the TV\'s list…';
    try {
      const r = await fetch(`${RELAY}/${topicTv(code)}/json?poll=1&since=12h`);
      if (!r.ok) throw new Error('relay returned ' + r.status);
      const lines = (await r.text()).split('\n').filter(Boolean).map(l => { try { return JSON.parse(l); } catch (e) { return null; } });
      const msg = lines.filter(m => m && m.event === 'message').pop();
      if (!msg) { s.textContent = 'Nothing from the TV yet. Open Sports Command Center on the TV and try again.'; return; }
      const payload = await decode(msg.message);
      items = (payload.items || []).map(it => it.type === 'player' ? Object.assign({ props: [] }, it) : it);
      dirty = false; save(); render();
      s.textContent = `Loaded ${items.length} item${items.length === 1 ? '' : 's'} from the TV.`;
    } catch (e) {
      s.textContent = 'Couldn\'t load from the TV: ' + e.message;
    }
  }
  $('loadTvBtn').addEventListener('click', loadFromTv);

  // ---------- send ----------
  $('sendBtn').addEventListener('click', async () => {
    if (!code) return;
    $('sendBtn').disabled = true;
    renderStatus('Sending…', '');
    try {
      const body = await encode({ v: 1, items, sentAt: Date.now() });
      if (body.length > 4000) throw new Error('the list is too long for one message. Remove a few items and try again.');
      const r = await fetch(`${RELAY}/${topicIn(code)}`, { method: 'POST', body });
      if (!r.ok) throw new Error('relay returned ' + r.status);
      dirty = false; lastSent = Date.now(); store.set('lastSent', lastSent); save();
      renderStatus();
    } catch (e) {
      renderStatus('Not sent: ' + e.message, 'dirty');
    } finally {
      $('sendBtn').disabled = !code;
    }
  });

  // ---------- add flow ----------
  const dlg = $('addDialog');
  let addStack = [];
  function showStep(step) {
    $('addTitle').textContent = step.title;
    $('addBack').style.visibility = addStack.length > 1 ? 'visible' : 'hidden';
    const search = $('addSearch');
    $('addSearchWrap').hidden = !step.search;
    search.value = '';
    search.placeholder = step.search || '';
    const body = $('addBody');
    body.className = 'picker' + (step.cls ? ' ' + step.cls : '');
    body.innerHTML = '<p class="muted">Loading…</p>';
    Promise.resolve(step.load()).then(rows => {
      const draw = q => {
        const shown = q ? rows.filter(r => r.text.toLowerCase().includes(q.toLowerCase())) : rows;
        body.innerHTML = shown.length ? '' : '<p class="muted">No matches.</p>';
        shown.forEach(r => {
          const b = document.createElement('button');
          b.type = 'button'; b.className = 'pick' + (r.done ? ' done' : '');
          b.innerHTML = r.html; b.addEventListener('click', r.onClick);
          body.appendChild(b);
        });
      };
      draw('');
      search.oninput = () => draw(search.value);
    }).catch(err => { body.innerHTML = `<p class="error">Couldn't load from ESPN: ${esc(err.message)}</p>`; });
  }
  function push(step) { addStack.push(step); showStep(step); }
  $('addBack').addEventListener('click', () => { if (addStack.length > 1) { addStack.pop(); showStep(addStack[addStack.length - 1]); } });
  $('addClose').addEventListener('click', () => dlg.close());
  $('addBtn').addEventListener('click', () => { addStack = []; push(leagueStep()); dlg.showModal(); });

  function leagueStep() {
    return {
      title: 'Pick a league', cls: 'leagues',
      load: () => LEAGUES.map(l => ({ text: l.label, html: `<b>${esc(l.label)}</b>${l.kind === 'soccer' ? '<span class="note">teams</span>' : ''}`, onClick: () => push(teamStep(l)) })),
    };
  }
  function teamStep(league) {
    return {
      title: league.label, search: 'Search teams',
      load: async () => (await teams(league)).map(t => {
        const done = items.some(it => it.type === 'team' && it.league === league.id && it.teamId === t.id);
        return {
          text: t.name + ' ' + t.abbreviation, done,
          html: `${pic(t.logo, true)}<span>${esc(t.name)}</span>${done ? '<span class="note">following</span>' : ''}`,
          onClick: () => league.kind === 'soccer' ? addTeam(league, t) : push(teamChoiceStep(league, t)),
        };
      }),
    };
  }
  function teamChoiceStep(league, t) {
    return {
      title: t.name, cls: 'choice',
      load: () => [
        { text: '', html: `Follow the ${esc(t.short)}: live score &amp; scoring alerts`, onClick: () => addTeam(league, t) },
        { text: '', html: `Pick a ${esc(t.short)} player (props &amp; fantasy)`, onClick: () => push(playerStep(league, t)) },
      ],
    };
  }
  function playerStep(league, t) {
    return {
      title: t.name, search: 'Search players',
      load: async () => (await roster(league, t.id)).map(a => {
        const done = items.some(it => it.type === 'player' && it.league === league.id && it.athleteId === a.id);
        return {
          text: a.name, done,
          html: `${pic(a.headshot)}<span>${esc(a.name)}<br><span class="sub">${esc([a.position, a.jersey ? '#' + a.jersey : null].filter(Boolean).join(' · '))}</span></span>${done ? '<span class="note">following</span>' : ''}`,
          onClick: () => addPlayer(league, t, a),
        };
      }),
    };
  }
  function addTeam(league, t) {
    const it = { type: 'team', league: league.id, teamId: t.id, abbreviation: t.abbreviation, name: t.name, logo: t.logo, color: t.color };
    if (!items.some(x => keyOf(x) === keyOf(it))) items.push(it);
    dlg.close(); changed();
  }
  function addPlayer(league, t, a) {
    const it = {
      type: 'player', league: league.id, athleteId: a.id, name: a.name, teamId: t.id, teamAbbreviation: t.abbreviation,
      position: a.position, jersey: a.jersey, headshot: a.headshot, props: [],
    };
    let i = items.findIndex(x => keyOf(x) === keyOf(it));
    if (i < 0) { items.push(it); i = items.length - 1; }
    dlg.close(); changed();
    openProp(i);
  }

  // ---------- prop form ----------
  const pdlg = $('propDialog');
  let propFor = -1;
  function openProp(i) {
    propFor = i;
    const it = items[i];
    $('propTitle').textContent = 'Add a prop: ' + it.name;
    $('propStat').innerHTML = statsFor(it.league).map(s => `<option value="${s[0]}">${esc(s[1])}</option>`).join('');
    $('propLine').value = '0.5';
    $('sideOver').checked = true;
    pdlg.showModal();
  }
  $('propStat').addEventListener('change', () => { if ($('propStat').value.startsWith('fpts') && $('propLine').value === '0.5') $('propLine').value = '15.5'; });
  $('propCancel').addEventListener('click', () => pdlg.close());
  $('propForm').addEventListener('submit', e => {
    e.preventDefault();
    const line = parseFloat($('propLine').value);
    if (!(line >= 0)) return;
    const side = $('sideUnder').checked ? 'UNDER' : 'OVER';
    items[propFor].props = (items[propFor].props || []).concat({ statId: $('propStat').value, line, side });
    pdlg.close(); changed();
  });

  // ---------- start ----------
  const fromUrl = new URLSearchParams(location.search).get('tv');
  if (fromUrl && normalize(fromUrl).length === 8 && normalize(fromUrl) !== code) link(fromUrl);
  else if (fromUrl) $('codeInput').value = pretty(fromUrl);
  render(); renderLink();
})();
