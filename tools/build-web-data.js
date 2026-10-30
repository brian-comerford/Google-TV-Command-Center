// Saves every league's teams and rosters as static JSON under web/data/, so the phone site
// never has to call ESPN from the browser (ESPN doesn't allow that). Run by .github/workflows/pages.yml.
// Usage: node tools/build-web-data.js [outDir]
'use strict';
const fs = require('fs');
const path = require('path');
const { LEAGUES, teamsUrl, rosterUrl, parseTeams, parseRoster } = require('../web/espn.js');

const OUT = path.resolve(process.argv[2] || path.join(__dirname, '..', 'web', 'data'));
const CONCURRENCY = 6;

async function getJson(url) {
  for (let attempt = 1; ; attempt++) {
    try {
      const r = await fetch(url, { headers: { 'User-Agent': 'SportsCommandCenter/1.0', Accept: 'application/json' } });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return await r.json();
    } catch (e) {
      if (attempt >= 3) throw new Error(`${url}: ${e.message}`);
      await new Promise(res => setTimeout(res, 1000 * attempt));
    }
  }
}

async function pool(items, worker) {
  let next = 0;
  const run = async () => { while (next < items.length) { const i = next++; await worker(items[i]); } };
  await Promise.all(Array.from({ length: CONCURRENCY }, run));
}

function write(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data));
}

// Sleeper (fantasy) players matched to ESPN, so the phone site and the TV can follow a Sleeper lineup:
// {"<sleeper id>": [espnAthleteId, espnTeamId, teamAbbr, name, position, headshot]}. Sleeper's own list is
// about 5 MB; this keeps only active skill players and kickers that are on an ESPN roster.
const SLEEPER_POSITIONS = new Set(['QB', 'RB', 'WR', 'TE', 'K']);
const SLEEPER_TO_ESPN_TEAM = { WAS: 'WSH' };
const nameKey = n => String(n || '').toLowerCase().replace(/\b(jr|sr|ii|iii|iv|v)\b\.?/g, '').replace(/[^a-z]/g, '');

async function buildSleeper(nflPlayers) {
  const all = await getJson('https://api.sleeper.app/v1/players/nfl');
  const byId = new Map(nflPlayers.map(p => [p.id, p]));
  const byName = new Map(nflPlayers.map(p => [nameKey(p.name) + '|' + p.teamAbbr, p]));
  const out = {};
  let viaId = 0, viaName = 0;
  for (const [sid, sp] of Object.entries(all)) {
    if (!sp || !sp.active || !sp.team || !SLEEPER_POSITIONS.has(sp.position)) continue;
    let p = sp.espn_id != null ? byId.get(String(sp.espn_id)) : null;
    if (p) viaId++;
    else {
      p = byName.get(nameKey(sp.full_name || `${sp.first_name} ${sp.last_name}`) + '|' + (SLEEPER_TO_ESPN_TEAM[sp.team] || sp.team));
      if (p) viaName++;
    }
    if (p) out[sid] = [p.id, p.teamId, p.teamAbbr, p.name, p.position || sp.position, p.headshot || null];
  }
  return { out, viaId, viaName };
}

(async () => {
  const summary = [];
  const nflPlayers = [];
  let failedLeagues = 0;
  for (const league of LEAGUES) {
    let teams;
    try {
      teams = parseTeams(await getJson(teamsUrl(league)));
    } catch (e) {
      console.error(`${league.id}: teams failed: ${e.message}`);
      failedLeagues++;
      continue;
    }
    write(path.join(OUT, league.id, 'teams.json'), teams);
    let rosters = 0, players = 0, missing = 0;
    if (league.kind !== 'soccer') {
      await pool(teams, async t => {
        try {
          const list = parseRoster(await getJson(rosterUrl(league, t.id)));
          write(path.join(OUT, league.id, `roster-${t.id}.json`), list);
          if (league.id === 'NFL') list.forEach(a => nflPlayers.push(Object.assign({ teamId: t.id, teamAbbr: t.abbreviation }, a)));
          rosters++; players += list.length;
        } catch (e) {
          missing++; // small schools sometimes have no roster; the site falls back to live ESPN
        }
      });
    }
    const line = `${league.id}: ${teams.length} teams, ${rosters} rosters, ${players} players${missing ? `, ${missing} rosters unavailable` : ''}`;
    console.log(line);
    summary.push(line);
  }
  if (nflPlayers.length) {
    try {
      const { out, viaId, viaName } = await buildSleeper(nflPlayers);
      write(path.join(OUT, 'NFL', 'sleeper-players.json'), out);
      const line = `Sleeper: ${Object.keys(out).length} players matched to ESPN (${viaId} by id, ${viaName} by name and team)`;
      console.log(line); summary.push(line);
    } catch (e) {
      console.error(`Sleeper: failed: ${e.message}`); // the phone site then matches players by name itself
    }
  }
  write(path.join(OUT, 'built.json'), { builtAt: new Date().toISOString(), summary });
  // Publish whatever we got; only fail when ESPN gave us nothing at all.
  if (failedLeagues === LEAGUES.length) process.exit(1);
})();
