// Phone remote for Sports Command Center.
// Builds the same watchlist JSON the TV stores, and sends it through the ntfy.sh relay to the
// TV's channel. Keep LEAGUES / STATS in sync with core/src/main/kotlin/com/sportscc/core/{League,StatCatalog}.kt.
(function () {
  'use strict';

  const RELAY = 'https://ntfy.sh';
  const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

  const { LEAGUES, teamsUrl, rosterUrl, parseTeams, parseRoster } = window.EspnShared;
  const STATS = {
    football: [
      ['pass_yds', 'Passing Yards', 'Pass Yds'], ['pass_td', 'Passing TDs', 'Pass TD'], ['pass_cmp', 'Completions', 'Cmp'], ['pass_att', 'Pass Attempts', 'Pass Att'],
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
  // The live score a Fantasy card shows (matches StatCatalog.fantasy on the TV).
  const fantasyLabel = leagueId => ({ football: 'PPR', basketball: 'DraftKings' })[leagueById(leagueId).kind] || '';
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

  // ---------- teams & rosters ----------
  // Browsers can't call ESPN directly from another site (no CORS), so a daily GitHub Action saves
  // every league's teams and rosters next to this page (tools/build-web-data.js). Live ESPN is a fallback.
  const cache = {};
  function getJson(url) {
    if (!cache[url]) {
      cache[url] = fetch(url).then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
      cache[url].catch(() => delete cache[url]);
    }
    return cache[url];
  }
  async function firstOf(local, live, parse) {
    try { return await getJson(local); } catch (e) { /* not built yet: try ESPN itself */ }
    try { return parse(await getJson(live)); } catch (e) { throw new Error('the team data isn\'t available yet. Try again in a few minutes.'); }
  }
  const teams = league => firstOf(`data/${league.id}/teams.json`, teamsUrl(league), parseTeams);
  const roster = (league, teamId) => firstOf(`data/${league.id}/roster-${teamId}.json`, rosterUrl(league, teamId), parseRoster);

  // ---------- render list ----------
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
  function pic(src, isLogo) {
    return src ? `<img class="pic${isLogo ? ' logo' : ''}" src="${esc(src)}" alt="" loading="lazy">` : `<div class="pic"></div>`;
  }
  // The TV shows cards in this order (with "My order"; "Live games first" still lifts live games).
  function moveBtns(i) {
    return `<button class="x mv" data-move="${i}:-1" aria-label="Move up"${i === 0 ? ' disabled' : ''}>↑</button>` +
      `<button class="x mv" data-move="${i}:1" aria-label="Move down"${i === items.length - 1 ? ' disabled' : ''}>↓</button>`;
  }
  function render() {
    $('list').innerHTML = items.map((it, i) => {
      const league = leagueById(it.league);
      if (it.type === 'team') {
        return `<div class="item"><div class="item-head">${pic(it.logo, true)}
          <div class="who"><div class="name">${esc(it.name)}</div><div class="sub"><span class="league">${esc(league.label)}</span> Team · scores &amp; scoring alerts</div></div>
          ${moveBtns(i)}<button class="x" data-remove="${i}" aria-label="Remove ${esc(it.name)}">×</button></div></div>`;
      }
      const props = (it.props || []).map((p, j) =>
        `<span class="prop${p.side === 'UNDER' ? ' under' : ''}"><b>${p.side === 'UNDER' ? 'U' : 'O'} ${num(p.line)}</b> ${esc(statShort(it.league, p.statId))}
          <button class="x" data-unprop="${i}:${j}" aria-label="Remove prop">×</button></span>`).join('');
      return `<div class="item"><div class="item-head">${pic(it.headshot)}
          <div class="who"><div class="name">${esc(it.name)}</div><div class="sub"><span class="league">${esc(league.label)}</span> ${esc([it.teamAbbreviation, it.position, it.jersey ? '#' + it.jersey : null].filter(Boolean).join(' · '))}</div></div>
          ${moveBtns(i)}<button class="x" data-remove="${i}" aria-label="Remove ${esc(it.name)}">×</button></div>
        ${it.fantasy
          ? `<div class="props"><span class="prop fantasy"><b>Fantasy</b> live stats${fantasyLabel(it.league) ? ' + ' + esc(fantasyLabel(it.league)) : ''}</span><button class="addprop" data-mode="${i}" type="button">Switch to bets</button></div></div>`
          : `<div class="props">${props}<button class="addprop" data-addprop="${i}" type="button">+ Prop</button><button class="addprop subtle" data-mode="${i}" type="button">Fantasy instead</button></div></div>`}`;
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
    else if (t.dataset.move) {
      const [i, d] = t.dataset.move.split(':').map(Number);
      const j = i + d;
      if (j < 0 || j >= items.length) return;
      [items[i], items[j]] = [items[j], items[i]];
      changed();
      const again = document.querySelector(`[data-move="${j}:${d}"]`) || document.querySelector(`[data-move="${j}:${-d}"]`);
      if (again) again.focus();
    }
    else if (t.dataset.unprop) { const [i, j] = t.dataset.unprop.split(':').map(Number); items[i].props.splice(j, 1); changed(); }
    else if (t.dataset.addprop) openProp(+t.dataset.addprop);
    else if (t.dataset.mode) {
      const it = items[+t.dataset.mode];
      it.fantasy = !it.fantasy;
      if (it.fantasy) it.props = []; // fantasy players have no over/under lines
      changed();
      if (!it.fantasy) openProp(+t.dataset.mode);
    }
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
    }).catch(err => { body.innerHTML = `<p class="error">Couldn't load the list: ${esc(err.message)}</p>`; });
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
          onClick: () => push(modeStep(league, t, a)),
        };
      }),
    };
  }
  function addTeam(league, t) {
    const it = { type: 'team', league: league.id, teamId: t.id, abbreviation: t.abbreviation, name: t.name, logo: t.logo, color: t.color };
    if (!items.some(x => keyOf(x) === keyOf(it))) items.push(it);
    dlg.close(); changed();
  }
  function modeStep(league, t, a) {
    const score = fantasyLabel(league.id);
    return {
      title: a.name, cls: 'choice',
      load: () => [
        { text: '', html: `Fantasy: live stats${score ? ' and ' + esc(score) + ' score' : ''}<br><span class="sub">No lines to set</span>`, onClick: () => addPlayer(league, t, a, true) },
        { text: '', html: 'Bets: add over/under lines', onClick: () => addPlayer(league, t, a, false) },
      ],
    };
  }
  function addPlayer(league, t, a, fantasy) {
    const it = {
      type: 'player', league: league.id, athleteId: a.id, name: a.name, teamId: t.id, teamAbbreviation: t.abbreviation,
      position: a.position, jersey: a.jersey, headshot: a.headshot, props: [], fantasy,
    };
    let i = items.findIndex(x => keyOf(x) === keyOf(it));
    if (i < 0) { items.push(it); i = items.length - 1; } else { items[i].fantasy = fantasy; if (fantasy) items[i].props = []; }
    dlg.close(); changed();
    if (!fantasy) openProp(i);
  }

  // ---------- import from Player Prop Finder ----------
  // That app publishes its picks as static JSON on GitHub Pages (github.com/brian-comerford/Player-Prop-Finder).
  // Its player ids are nflverse ids, so players are matched to ESPN by name and team.
  const PROP_FINDER = 'https://brian-comerford.github.io/Player-Prop-Finder/data/';
  const PF_MARKETS = {
    player_pass_yds: 'pass_yds', player_pass_tds: 'pass_td', player_pass_attempts: 'pass_att',
    player_pass_interceptions: 'pass_int', player_rush_yds: 'rush_yds', player_rush_attempts: 'rush_att',
    player_receptions: 'rec', player_reception_yds: 'rec_yds', player_rush_reception_yds: 'rush_rec_yds',
    player_anytime_td: 'any_td',
  };
  const NFLVERSE_TO_ESPN_TEAM = { LA: 'LAR', WAS: 'WSH' };
  const nameKey = n => String(n).toLowerCase().replace(/\b(jr|sr|ii|iii|iv|v)\b\.?/g, '').replace(/[^a-z]/g, '');

  const idlg = $('importDialog');
  let picks = [], conf = 'all';
  const selected = new Set();

  async function loadPicks() {
    const get = f => fetch(PROP_FINDER + f + '?v=' + Date.now()).then(r => r.ok ? r.json() : null).catch(() => null);
    const [props, tds, meta] = await Promise.all([get('props.json'), get('anytime_td_props.json'), get('meta.json')]);
    if (!props && !tds) throw new Error("couldn't reach Player Prop Finder");
    const all = (props || []).concat(tds || []);
    picks = all
      .filter(p => PF_MARKETS[p.market] && p.recommended_edge > 0)
      .map((p, i) => ({
        id: i, raw: p, statId: PF_MARKETS[p.market],
        line: p.line == null ? 0.5 : p.line,
        side: p.recommended_side === 'under' ? 'UNDER' : 'OVER',
        price: p.recommended_side === 'under' ? p.price_under : p.price_over,
      }))
      .sort((a, b) => b.raw.recommended_edge - a.raw.recommended_edge);
    return meta;
  }
  function havePick(pk) {
    return items.some(it => it.type === 'player' && it.league === 'NFL' && nameKey(it.name) === nameKey(pk.raw.player_name) &&
      (it.props || []).some(p => p.statId === pk.statId && p.line === pk.line && p.side === pk.side));
  }
  function renderPicks() {
    const shown = picks.filter(p => conf === 'all' || p.raw.confidence === 'High' || (conf === 'Medium' && p.raw.confidence === 'Medium'));
    $('importList').innerHTML = shown.length ? shown.map(pk => {
      const p = pk.raw, have = havePick(pk);
      const price = pk.price == null ? '' : ` · ${pk.price > 0 ? '+' : ''}${pk.price}`;
      const what = pk.statId === 'any_td' ? 'Anytime TD' : `${pk.side === 'UNDER' ? 'Under' : 'Over'} ${num(pk.line)} ${esc(p.market_label)}`;
      return `<label class="pickrow${have ? ' have' : ''}">
        <input type="checkbox" data-pick="${pk.id}"${selected.has(pk.id) ? ' checked' : ''}${have ? ' disabled' : ''}>
        <span style="min-width:0"><span class="what">${esc(p.player_name)}</span> <span class="sub">${esc(p.team)} vs ${esc(p.opponent)}</span><br>
          ${what}<span class="sub">${price}</span><span class="conf ${esc(p.confidence)}">${esc(p.confidence)}</span>${have ? ' <span class="sub">· on your list</span>' : ''}</span>
        <span class="edge">+${(p.recommended_edge * 100).toFixed(1)}%<br><span class="sub">edge</span></span>
      </label>`;
    }).join('') : '<p class="muted">No picks match this filter.</p>';
    $('importCount').textContent = `${selected.size} selected`;
    $('importGo').disabled = !selected.size;
  }
  $('importList').addEventListener('change', e => {
    const id = Number(e.target.dataset.pick);
    if (e.target.checked) selected.add(id); else selected.delete(id);
    $('importCount').textContent = `${selected.size} selected`;
    $('importGo').disabled = !selected.size;
  });
  $('importFilters').addEventListener('click', e => {
    const b = e.target.closest('[data-conf]'); if (!b) return;
    conf = b.dataset.conf;
    document.querySelectorAll('#importFilters .chip-btn').forEach(x => x.classList.toggle('on', x === b));
    renderPicks();
  });
  $('importClose').addEventListener('click', () => idlg.close());
  $('importBtn').addEventListener('click', async () => {
    selected.clear();
    $('importList').innerHTML = '';
    $('importMeta').textContent = "Loading today's picks…";
    idlg.showModal();
    try {
      const meta = await loadPicks();
      const when = meta && meta.upcoming_week ? `Week ${meta.upcoming_week} · ` : '';
      const sample = meta && meta.odds_source === 'sample' ? ' <span class="warnbox">These use SAMPLE odds, not real sportsbook lines.</span>' : '';
      $('importMeta').innerHTML = `${when}${picks.length} picks with a positive edge, best first. Tick the ones you bet.${sample}`;
      renderPicks();
    } catch (e) {
      $('importMeta').innerHTML = `<span class="error">Couldn't load picks: ${esc(e.message)}.</span>`;
    }
  });

  async function findNflPlayer(name, team) {
    const nfl = leagueById('NFL');
    const all = await teams(nfl);
    const abbr = NFLVERSE_TO_ESPN_TEAM[team] || team;
    // Their team first; then everyone else, in case of a recent trade.
    const order = all.filter(t => t.abbreviation === abbr).concat(all.filter(t => t.abbreviation !== abbr));
    for (const t of order) {
      let list;
      try { list = await roster(nfl, t.id); } catch (e) { continue; }
      const a = list.find(x => nameKey(x.name) === nameKey(name));
      if (a) return { team: t, athlete: a };
    }
    return null;
  }
  $('importGo').addEventListener('click', async () => {
    const chosen = picks.filter(p => selected.has(p.id));
    $('importGo').disabled = true;
    $('importCount').textContent = 'Adding…';
    const missed = [];
    for (const pk of chosen) {
      const found = await findNflPlayer(pk.raw.player_name, pk.raw.team).catch(() => null);
      if (!found) { missed.push(pk.raw.player_name); continue; }
      const { team: t, athlete: a } = found;
      const key = `player:NFL:${a.id}`;
      let it = items.find(x => keyOf(x) === key);
      if (!it) {
        it = { type: 'player', league: 'NFL', athleteId: a.id, name: a.name, teamId: t.id, teamAbbreviation: t.abbreviation,
          position: a.position, jersey: a.jersey, headshot: a.headshot, props: [] };
        items.push(it);
      }
      it.props = it.props || [];
      it.fantasy = false; // a bet line makes this a bets card
      if (!it.props.some(p => p.statId === pk.statId && p.line === pk.line && p.side === pk.side)) {
        it.props.push({ statId: pk.statId, line: pk.line, side: pk.side });
      }
    }
    changed();
    selected.clear();
    if (missed.length) {
      $('importMeta').innerHTML = `<span class="warnbox">Added ${chosen.length - missed.length}. Couldn't find ${esc(missed.join(', '))} on an ESPN roster. Add them with + Add instead.</span>`;
      renderPicks();
    } else {
      idlg.close();
      renderStatus(`Added ${chosen.length} pick${chosen.length === 1 ? '' : 's'}. Tap Send to TV.`, 'dirty');
    }
  });

  // ---------- prop form ----------
  const pdlg = $('propDialog');
  let propFor = -1;
  function openProp(i) {
    propFor = i;
    const it = items[i];
    $('propTitle').textContent = 'Add a prop: ' + it.name;
    // Fantasy points aren't offered as a line: follow the player as Fantasy instead.
    $('propStat').innerHTML = statsFor(it.league).filter(st => !st[0].startsWith('fpts')).map(s => `<option value="${s[0]}">${esc(s[1])}</option>`).join('');
    $('propLine').value = '0.5';
    $('sideOver').checked = true;
    pdlg.showModal();
  }
  $('propCancel').addEventListener('click', () => pdlg.close());
  $('propForm').addEventListener('submit', e => {
    e.preventDefault();
    const line = parseFloat($('propLine').value);
    if (!(line >= 0)) return;
    const side = $('sideUnder').checked ? 'UNDER' : 'OVER';
    items[propFor].props = (items[propFor].props || []).concat({ statId: $('propStat').value, line, side });
    pdlg.close(); changed();
  });

  // ---------- install / update card ----------
  $('copyApk').addEventListener('click', async () => {
    const input = $('apkUrl');
    try {
      await navigator.clipboard.writeText(input.value);
      $('copyStatus').textContent = 'Copied. Now paste it with the Google TV app keyboard.';
    } catch (e) {
      input.focus(); input.select(); input.setSelectionRange(0, input.value.length);
      $('copyStatus').textContent = 'Press and hold the selected link, then tap Copy.';
    }
  });
  fetch('https://api.github.com/repos/brian-comerford/Google-TV-Command-Center/releases/latest')
    .then(r => r.ok ? r.json() : null)
    .then(rel => { if (rel && rel.tag_name) $('latestVersion').textContent = 'Latest: ' + rel.tag_name; })
    .catch(() => { /* version label is optional */ });

  // ---------- start ----------
  const fromUrl = new URLSearchParams(location.search).get('tv');
  if (fromUrl && normalize(fromUrl).length === 8 && normalize(fromUrl) !== code) link(fromUrl);
  else if (fromUrl) $('codeInput').value = pretty(fromUrl);
  // Open the install steps until a TV has been linked (you need the app before you can link).
  $('installCard').open = !code;
  render(); renderLink();
})();
