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

(async () => {
  const summary = [];
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
  write(path.join(OUT, 'built.json'), { builtAt: new Date().toISOString(), summary });
  // Publish whatever we got; only fail when ESPN gave us nothing at all.
  if (failedLeagues === LEAGUES.length) process.exit(1);
})();
