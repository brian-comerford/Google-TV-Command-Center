// Shared by the phone site (window.EspnShared) and tools/build-web-data.js (require).
// Keep LEAGUES in sync with core/src/main/kotlin/com/sportscc/core/League.kt.
(function (root) {
  'use strict';
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
  const ESPN = 'https://site.api.espn.com/apis/site/v2/sports';
  const teamsUrl = l => `${ESPN}/${l.sport}/${l.path}/teams?limit=1000`;
  const rosterUrl = (l, teamId) => `${ESPN}/${l.sport}/${l.path}/teams/${teamId}/roster`;

  function parseTeams(j) {
    const list = ((((j.sports || [])[0] || {}).leagues || [])[0] || {}).teams || [];
    return list.map(w => w.team || w).filter(t => t && t.id).map(t => ({
      id: String(t.id), abbreviation: t.abbreviation || '', name: t.displayName || t.name || '',
      short: t.shortDisplayName || t.name || '', logo: ((t.logos || [])[0] || {}).href || null, color: t.color || null,
    })).sort((a, b) => a.name.localeCompare(b.name));
  }
  // Football rosters are grouped ({position, items: [...]}); other sports are a flat list.
  function parseRoster(j) {
    const flat = (j.athletes || []).flatMap(a => Array.isArray(a.items) ? a.items : [a]);
    return flat.filter(a => a && a.id).map(a => ({
      id: String(a.id), name: a.displayName || a.fullName || '', position: (a.position || {}).abbreviation || null,
      jersey: a.jersey || null, headshot: (a.headshot || {}).href || null,
    }));
  }

  const api = { LEAGUES, teamsUrl, rosterUrl, parseTeams, parseRoster };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.EspnShared = api;
})(this);
