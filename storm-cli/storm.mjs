#!/usr/bin/env node
// Storm CLI - AI assistant configuration tool
// https://orm.st
//
// Zero dependencies. Requires Node.js 18+.

import { writeFileSync, appendFileSync, mkdirSync, existsSync, readFileSync, readdirSync, unlinkSync, rmdirSync, statSync } from 'fs';
import { basename, join, dirname } from 'path';
import { homedir } from 'os';
import { execSync } from 'child_process';

const VERSION = '1.11.0';

// ─── ANSI ────────────────────────────────────────────────────────────────────

const RESET       = '\x1b[0m';
const BOLD        = '\x1b[1m';
const DIM         = '\x1b[2m';
const HIDE_CURSOR = '\x1b[?25l';
const SHOW_CURSOR = '\x1b[?25h';
const CLEAR       = '\x1b[2J';
const HOME        = '\x1b[H';
const CLEAR_DOWN  = '\x1b[J';

const DB          = '\x1b[38;5;244m';
const DB_DIM      = '\x1b[38;5;242m';
const DB_GLOW     = '\x1b[38;5;247m';
const DB_DIM_GLOW = '\x1b[38;5;243m';
const BOLT_BASE   = '\x1b[38;5;226m';
const BOLT_WARM   = '\x1b[38;5;227m';
const BOLT_HOT    = '\x1b[38;5;229m';
const BOLT_CORE   = '\x1b[38;5;230m';
const BOLT_WHITE  = '\x1b[38;5;231m';
const WHITE       = '\x1b[97m';
const GRAY        = '\x1b[38;5;245m';
const YELLOW_228  = '\x1b[38;5;228m';

function bold(text)       { return `${BOLD}${text}${RESET}`; }
function dimText(text)    { return `${DIM}${text}${RESET}`; }
function boltYellow(text) { return `${YELLOW_228}${text}${RESET}`; }

// Ensure terminal cursor is always restored on exit.
process.on('exit', () => process.stdout.write(SHOW_CURSOR));

// ─── Welcome screen ─────────────────────────────────────────────────────────

const BODY_SOURCE = `
     @@@@@@@@@@@@@@@@@@@@
 @@@@                    @@@@
@@@                #       @@@
@@@@@            ###      @@@@
@@@@@@@@@@@@@@  ###  @@@@@@@@@
 @@@@@@@@@@@@  ###  @@@@@@@@@
@   @@@@@@@@  ###  @@@@@@@   @
@@@@        ####          @@@@
@@@@@@@@@  ####      @@@@@@@@@
 @@@@@@@  ##########  @@@@@@@
@     @@ #########   @@@     @
@@@@@        ####        @@@@@
@@@@@@@@@@  ####  @@@@@@@@@@@@
 @@@@@@@@@ ###  @@@@@@@@@@@@@
    @@@@@ ###  @@@@@@@@@@@
         ###
        ##
`.trimEnd();

const bodyLines   = BODY_SOURCE.replace(/^\n/, '').split('\n');
const artWidth    = Math.max(...bodyLines.map(s => s.length));
const artHeight   = bodyLines.length;
const paddedLines = Array.from({ length: artHeight }, (_, i) =>
  (bodyLines[i] || '').padEnd(artWidth, ' '),
);

function hasBody(x, y) {
  if (y < 0 || y >= artHeight || x < 0 || x >= artWidth) return false;
  const ch = paddedLines[y][x];
  return ch === '@' || ch === '#';
}

function hasBolt(x, y) {
  if (y < 0 || y >= artHeight || x < 0 || x >= artWidth) return false;
  return paddedLines[y][x] === '#';
}

const boltCells = [];
for (let y = 0; y < artHeight; y++)
  for (let x = 0; x < artWidth; x++)
    if (hasBolt(x, y)) boltCells.push({ x, y });

const minBoltY    = Math.min(...boltCells.map(c => c.y));
const maxBoltY    = Math.max(...boltCells.map(c => c.y));
const boltSpan    = Math.max(1, maxBoltY - minBoltY);
const boltProgress = new Map();
for (const { x, y } of boltCells)
  boltProgress.set(`${x},${y}`, (y - minBoltY) / boltSpan);

const visibleRows = [];
for (let y = 0; y < artHeight; y++)
  if ([...paddedLines[y]].some(ch => ch === '@' || ch === '#')) visibleRows.push(y);
const firstVisibleRow       = visibleRows[0];
const secondVisibleRow      = visibleRows[1];
const penultimateVisibleRow = visibleRows[visibleRows.length - 2];
const lastVisibleRow        = visibleRows[visibleRows.length - 1];

function isDimDbRow(y) {
  return y === firstVisibleRow || y === secondVisibleRow ||
         y === penultimateVisibleRow || y === lastVisibleRow;
}

function dbColor(y, glowing) {
  const dimRow = isDimDbRow(y);
  if (glowing) return BOLD + (dimRow ? DB_DIM_GLOW : DB_GLOW);
  return BOLD + (dimRow ? DB_DIM : DB);
}

// --- Strike (lightning pulse) logic ---

let strikeActive   = false;
let strikeStart    = 0;
let strikeDuration = 0;
let nextStrike     = 0;
let strikeTail     = 0.18;

function scheduleStrike(now) { nextStrike = now + 400 + Math.random() * 3600; }

function startStrike(now) {
  strikeActive   = true;
  strikeStart    = now;
  strikeDuration = 200 + Math.random() * 280;
  strikeTail     = 0.14 + Math.random() * 0.1;
}

function clamp01(v) { return Math.max(0, Math.min(1, v)); }

function pulseIntensity(x, y, now) {
  if (!strikeActive) return 0;
  const p    = boltProgress.get(`${x},${y}`) ?? 0;
  const t    = clamp01((now - strikeStart) / strikeDuration);
  const head = clamp01(Math.pow(t, 2.25));
  const tail = Math.max(0, head - strikeTail);
  const jitter  = Math.sin(x * 0.9 + y * 1.7 + now * 0.018) * 0.006
                + Math.sin(x * 0.35 + now * 0.011) * 0.004;
  const sparkle = (x * 17 + y * 31 + Math.floor(now / 30)) % 37 === 0;
  const hd = Math.max(0, Math.abs(p - head) - jitter);
  const td = Math.max(0, Math.abs(p - tail) - jitter * 0.5);
  if (hd < 0.012) return 5;
  if (hd < 0.024) return 4;
  if (hd < 0.042) return 3;
  if (hd < 0.07)  return 2;
  if (hd < 0.1)   return 1;
  if (td < 0.016) return 4;
  if (td < 0.034) return 3;
  if (td < 0.058) return 2;
  if (td < 0.085) return 1;
  if (sparkle && p <= head && p >= Math.max(0, head - 0.1)) return 3;
  return 0;
}

function expandedPulseIntensity(x, y, now) {
  let best = pulseIntensity(x, y, now);
  for (let yy = y - 1; yy <= y + 1; yy++)
    for (let xx = x - 1; xx <= x + 1; xx++) {
      if (xx === x && yy === y) continue;
      if (!hasBolt(xx, yy)) continue;
      const n = pulseIntensity(xx, yy, now);
      if (n >= 3) best = Math.max(best, n - 1);
    }
  return best;
}

function boltColorCode(x, y, now) {
  const intensity = expandedPulseIntensity(x, y, now);
  if (intensity === 0) return DIM + BOLT_BASE;
  if (intensity === 1) return BOLD + BOLT_BASE;
  if (intensity === 2) return BOLD + BOLT_WARM;
  if (intensity === 3) return BOLD + BOLT_HOT;
  if (intensity === 4) return BOLD + BOLT_CORE;
  return BOLD + BOLT_WHITE;
}

function ringGlow(x, y, now) {
  for (let yy = y - 1; yy <= y + 1; yy++)
    for (let xx = x - 2; xx <= x + 2; xx++) {
      if (!hasBolt(xx, yy)) continue;
      if (expandedPulseIntensity(xx, yy, now) >= 3) return true;
    }
  return false;
}

// --- Matrix rain (demo mode) ---

let demoMode = false;
const matrixColumns = [];
const MATRIX_MARGIN = 12;

function newDrop() {
  return {
    y: -1 - Math.random() * artHeight * 0.6,
    speed: 0.06 + Math.random() * 0.14,
    trailLength: 3 + Math.floor(Math.random() * 7),
  };
}

function initMatrixRain() {
  matrixColumns.length = 0;
  const totalWidth = artWidth + 2 * MATRIX_MARGIN;
  for (let x = 0; x < totalWidth; x++) {
    const drops = [];
    const count = 1 + Math.floor(Math.random() * 3);
    for (let i = 0; i < count; i++) {
      const drop = newDrop();
      drop.y = Math.random() * artHeight * 2 - artHeight;
      drops.push(drop);
    }
    matrixColumns.push(drops);
  }
}

const MATRIX_GLYPHS = 'ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ';

function matrixGlyph(x, y, now) {
  const idx = (x * 17 + y * 31 + Math.floor(now / 80)) % MATRIX_GLYPHS.length;
  return MATRIX_GLYPHS[idx];
}

function updateMatrixRain() {
  for (const drops of matrixColumns) {
    for (const drop of drops) {
      drop.y += drop.speed;
      if (drop.y - drop.trailLength > artHeight + 3) {
        drop.y = -1 - Math.random() * artHeight * 0.6;
        drop.speed = 0.06 + Math.random() * 0.14;
        drop.trailLength = 3 + Math.floor(Math.random() * 7);
      }
    }
  }
}

function matrixIntensityAt(x, y) {
  const drops = matrixColumns[x];
  if (!drops) return 0;
  let best = 0;
  for (const drop of drops) {
    const dist = drop.y - y;
    if (dist < -0.5 || dist > drop.trailLength) continue;
    const t = Math.max(0, dist) / drop.trailLength;
    let intensity;
    if (dist < 0.5) intensity = 5;
    else if (t < 0.15) intensity = 4;
    else if (t < 0.35) intensity = 3;
    else if (t < 0.6) intensity = 2;
    else intensity = 1;
    if (intensity > best) best = intensity;
  }
  return best;
}

function matrixColor(intensity) {
  if (intensity === 1) return DIM + BOLT_BASE;
  if (intensity === 2) return BOLD + BOLT_BASE;
  if (intensity === 3) return BOLD + BOLT_WARM;
  if (intensity === 4) return BOLD + BOLT_HOT;
  return BOLD + BOLT_WHITE;
}

function matrixDbColor(intensity) {
  if (intensity === 1) return '\x1b[38;5;244m';
  if (intensity === 2) return '\x1b[38;5;245m';
  if (intensity === 3) return '\x1b[38;5;247m';
  if (intensity === 4) return '\x1b[38;5;248m';
  return '\x1b[38;5;249m';
}

// --- Text overlay ---

function stripAnsi(s) { return s.replace(/\x1b\[[0-9;]*m/g, ''); }

function centerLine(line, cols) {
  const pad = Math.max(0, Math.floor((cols - stripAnsi(line).length) / 2));
  return ' '.repeat(pad) + line;
}

const INIT_TEXT_LINES = [
  '',
  `${GRAY}Bootstrap your project for Storm ORM${RESET}`,
  '',
  `${BOLD}${BOLT_HOT}\u2022${WHITE} Install Storm rules and skills${RESET}`,
  `${BOLD}${BOLT_HOT}\u2022${WHITE} Storm MCP for database awareness and validation (optional)${RESET}`,
  '',
  `${GRAY}Press Enter to select tools${RESET}`,
];

const DEMO_TEXT_LINES = [
  '',
  `${GRAY}Storm Fu - Training Program${RESET}`,
  '',
  `${BOLD}${BOLT_HOT}\u2022${WHITE} Live demo of building an app with the Storm AI workflow${RESET}`,
  `${BOLD}${BOLT_HOT}\u2022${WHITE} Storm MCP enables database awareness and validation${RESET}`,
  '',
  `${GRAY}Press Enter to follow the white rabbit${RESET}`,
];

let activeTextLines = INIT_TEXT_LINES;

// --- Render ---

function renderFrame(now) {
  const cols = process.stdout.columns || 120;
  const rows = process.stdout.rows    || 40;
  const rendered = [];

  if (demoMode) updateMatrixRain();

  const startX = demoMode ? -MATRIX_MARGIN : 0;
  const endX   = demoMode ? artWidth + MATRIX_MARGIN : artWidth;

  for (let y = 0; y < artHeight; y++) {
    let out = '';
    for (let x = startX; x < endX; x++) {
      const inArt = x >= 0 && x < artWidth;
      if (!inArt || !hasBody(x, y)) {
        if (demoMode) {
          const mx = x + MATRIX_MARGIN;
          const mi = matrixIntensityAt(mx, y);
          if (mi >= 4)     out += '\x1b[38;5;237m' + matrixGlyph(mx, y, now) + RESET;
          else if (mi >= 2) out += '\x1b[38;5;235m' + matrixGlyph(mx, y, now) + RESET;
          else              out += '\x1b[38;5;233m' + matrixGlyph(mx, y, now) + RESET;
        } else out += ' ';
        continue;
      }
      if (demoMode) {
        const mx = x + MATRIX_MARGIN;
        const mi = matrixIntensityAt(mx, y);
        if (hasBolt(x, y)) {
          if (mi >= 3) out += matrixColor(mi) + matrixGlyph(mx, y, now) + RESET;
          else              out += dbColor(y, false) + '#' + RESET;
        } else {
          out += dbColor(y, false) + '@' + RESET;
        }
      } else {
        if (hasBolt(x, y))  out += boltColorCode(x, y, now) + '#' + RESET;
        else                 out += dbColor(y, ringGlow(x, y, now)) + '@' + RESET;
      }
    }
    if (out.trim().length > 0) rendered.push(centerLine(out, cols));
  }

  for (const tl of activeTextLines) rendered.push(centerLine(tl, cols));

  const topPad   = Math.max(0, Math.floor((rows - rendered.length) / 3));
  const blankLine = ' '.repeat(cols);
  let frame = '';
  for (let r = 0; r < rows; r++) {
    const ci = r - topPad;
    frame += (ci >= 0 && ci < rendered.length) ? rendered[ci] : blankLine;
    if (r < rows - 1) frame += '\n';
  }
  return frame;
}

async function printWelcome(customTextLines) {
  activeTextLines = customTextLines || INIT_TEXT_LINES;
  demoMode = customTextLines === DEMO_TEXT_LINES;
  if (demoMode) {
    initMatrixRain();
  } else {
    nextStrike = Date.now() + Math.random() * 100;
  }
  process.stdout.write(HIDE_CURSOR + CLEAR);

  const onResize = () => process.stdout.write(CLEAR);
  process.stdout.on('resize', onResize);

  const timer = setInterval(() => {
    const now = Date.now();
    if (!demoMode) {
      if (!strikeActive && now > nextStrike) startStrike(now);
      if (strikeActive && now > strikeStart + strikeDuration) {
        strikeActive = false;
        scheduleStrike(now);
      }
    }
    process.stdout.write(HOME);
    process.stdout.write(renderFrame(now));
  }, 20);

  await new Promise(resolve => {
    const { stdin } = process;
    const wasRaw = stdin.isRaw;
    if (stdin.isTTY) stdin.setRawMode(true);
    stdin.resume();
    stdin.once('data', data => {
      if (stdin.isTTY) stdin.setRawMode(wasRaw ?? false);
      stdin.pause();
      if (data[0] === 3) {
        clearInterval(timer);
        process.stdout.removeListener('resize', onResize);
        process.stdout.write(RESET + SHOW_CURSOR + CLEAR + HOME);
        process.exit(0);
      }
      resolve();
    });
  });

  clearInterval(timer);
  process.stdout.removeListener('resize', onResize);
  process.stdout.write(RESET + SHOW_CURSOR + CLEAR + HOME);
}

// ─── Prompts ─────────────────────────────────────────────────────────────────

async function checkbox({ message, choices }) {
  const { stdin, stdout } = process;

  if (!stdin.isTTY) {
    return choices.filter(c => c.checked).map(c => c.value);
  }

  return new Promise((resolve, reject) => {
    const selected = new Set();
    choices.forEach((c, i) => { if (c.checked) selected.add(i); });
    let cursor = 0;
    let linesWritten = 0;

    function render(final) {
      let out = '';
      if (linesWritten > 0) out += `\x1b[${linesWritten}A`;

      if (final) {
        out += CLEAR_DOWN;
        const names = choices.filter((_, i) => selected.has(i)).map(c => c.name).join(', ');
        out += `${boltYellow('\u2714')} ${bold(message)} ${boltYellow(names)}\n`;
        linesWritten = 1;
        stdout.write(out);
        return;
      }

      out += `\x1b[2K${boltYellow('?')} ${bold(message)}\n`;
      for (let i = 0; i < choices.length; i++) {
        const atCursor  = i === cursor;
        const isChecked = selected.has(i);
        const prefix = atCursor ? boltYellow('\u276f') : ' ';
        const check  = isChecked ? boltYellow('\u25c9') : dimText('\u25cb');
        const label  = atCursor  ? boltYellow(choices[i].name) : choices[i].name;
        out += `\x1b[2K  ${prefix} ${check} ${label}\n`;
      }
      out += `\x1b[2K${dimText('  Space to toggle, Enter to confirm')}\n`;
      linesWritten = choices.length + 2;
      stdout.write(out);
    }

    const wasRaw = stdin.isRaw;
    stdin.setRawMode(true);
    stdin.resume();
    stdout.write(HIDE_CURSOR);

    const onData = (data) => {
      const key = data.toString();

      if (key === '\x03') { // Ctrl+C
        cleanup();
        reject(Object.assign(new Error('User cancelled'), { name: 'ExitPromptError' }));
        return;
      }
      if (key === '\x1b[A') cursor = Math.max(0, cursor - 1);
      else if (key === '\x1b[B') cursor = Math.min(choices.length - 1, cursor + 1);
      else if (key === ' ') {
        if (selected.has(cursor)) selected.delete(cursor);
        else selected.add(cursor);
      } else if (key === '\r') {
        render(true);
        cleanup();
        resolve(choices.filter((_, i) => selected.has(i)).map(c => c.value));
        return;
      }
      render(false);
    };

    function cleanup() {
      stdin.removeListener('data', onData);
      stdin.setRawMode(wasRaw ?? false);
      stdin.pause();
      stdout.write(SHOW_CURSOR);
    }

    render(false);
    stdin.on('data', onData);
  });
}

async function confirm({ message, defaultValue = true }) {
  const { stdin, stdout } = process;

  if (!stdin.isTTY) {
    stdout.write(`${boltYellow('\u2714')} ${bold(message)} ${boltYellow(defaultValue ? 'Yes' : 'No')}\n`);
    return defaultValue;
  }

  return new Promise((resolve, reject) => {
    const hint = defaultValue ? 'Y/n' : 'y/N';

    function render(answer) {
      let out = '\r\x1b[2K';
      if (answer !== undefined) {
        out += `${boltYellow('\u2714')} ${bold(message)} ${boltYellow(answer ? 'Yes' : 'No')}\n`;
      } else {
        out += `${boltYellow('?')} ${bold(message)} ${dimText(`(${hint})`)} `;
      }
      stdout.write(out);
    }

    const wasRaw = stdin.isRaw;
    stdin.setRawMode(true);
    stdin.resume();

    const onData = (data) => {
      const key = data.toString().toLowerCase();

      if (key === '\x03') {
        cleanup();
        stdout.write('\n');
        reject(Object.assign(new Error('User cancelled'), { name: 'ExitPromptError' }));
        return;
      }

      let answer;
      if (key === 'y')       answer = true;
      else if (key === 'n')  answer = false;
      else if (key === '\r') answer = defaultValue;
      else return;

      render(answer);
      cleanup();
      resolve(answer);
    };

    function cleanup() {
      stdin.removeListener('data', onData);
      stdin.setRawMode(wasRaw ?? false);
      stdin.pause();
    }

    render(undefined);
    stdin.on('data', onData);
  });
}

async function select({ message, choices }) {
  const { stdin, stdout } = process;

  if (!stdin.isTTY) return choices[0].value;

  return new Promise((resolve, reject) => {
    let cursor = 0;
    let linesWritten = 0;

    function render(final) {
      let out = '';
      if (linesWritten > 0) out += `\x1b[${linesWritten}A`;

      if (final) {
        out += CLEAR_DOWN;
        out += `${boltYellow('\u2714')} ${bold(message)} ${boltYellow(choices[cursor].name)}\n`;
        linesWritten = 1;
        stdout.write(out);
        return;
      }

      out += `\x1b[2K${boltYellow('?')} ${bold(message)}\n`;
      for (let i = 0; i < choices.length; i++) {
        const atCursor = i === cursor;
        const prefix = atCursor ? boltYellow('\u276f') : ' ';
        const label  = atCursor ? boltYellow(choices[i].name) : choices[i].name;
        out += `\x1b[2K  ${prefix} ${label}\n`;
      }
      linesWritten = choices.length + 1;
      stdout.write(out);
    }

    const wasRaw = stdin.isRaw;
    stdin.setRawMode(true);
    stdin.resume();
    stdout.write(HIDE_CURSOR);

    const onData = (data) => {
      const key = data.toString();
      if (key === '\x03') {
        cleanup();
        reject(Object.assign(new Error('User cancelled'), { name: 'ExitPromptError' }));
        return;
      }
      if (key === '\x1b[A') cursor = Math.max(0, cursor - 1);
      else if (key === '\x1b[B') cursor = Math.min(choices.length - 1, cursor + 1);
      else if (key === '\r') {
        render(true);
        cleanup();
        resolve(choices[cursor].value);
        return;
      }
      render(false);
    };

    function cleanup() {
      stdin.removeListener('data', onData);
      stdin.setRawMode(wasRaw ?? false);
      stdin.pause();
      stdout.write(SHOW_CURSOR);
    }

    render(false);
    stdin.on('data', onData);
  });
}

async function textInput({ message, defaultValue = '', mask = false }) {
  const { stdin, stdout } = process;

  if (!stdin.isTTY) return defaultValue;

  return new Promise((resolve, reject) => {
    let buffer = '';

    function render(final) {
      let out = '\r\x1b[2K';
      if (final) {
        const value = buffer || defaultValue;
        const display = mask ? '\u2022'.repeat(value.length) : value;
        out += `${boltYellow('\u2714')} ${bold(message)} ${boltYellow(display)}\n`;
      } else {
        const display = mask ? '\u2022'.repeat(buffer.length) : buffer;
        const hint = defaultValue && !buffer ? dimText(` (${defaultValue})`) : '';
        out += `${boltYellow('?')} ${bold(message)}${hint} ${display}`;
      }
      stdout.write(out);
    }

    const wasRaw = stdin.isRaw;
    stdin.setRawMode(true);
    stdin.resume();

    const onData = (data) => {
      const key = data.toString();
      if (key === '\x03') {
        cleanup();
        stdout.write('\n');
        reject(Object.assign(new Error('User cancelled'), { name: 'ExitPromptError' }));
        return;
      }
      if (key === '\r') {
        render(true);
        cleanup();
        resolve(buffer || defaultValue);
        return;
      }
      if (key === '\x7f' || key === '\b') {
        buffer = buffer.slice(0, -1);
      } else if (key.length === 1 && key.charCodeAt(0) >= 32) {
        buffer += key;
      }
      render(false);
    };

    function cleanup() {
      stdin.removeListener('data', onData);
      stdin.setRawMode(wasRaw ?? false);
      stdin.pause();
    }

    render(false);
    stdin.on('data', onData);
  });
}

// ─── Project config (.storm.json) ────────────────────────────────────────────

const CONFIG_FILE = '.storm.json';

function readProjectConfig() {
  const configPath = join(process.cwd(), CONFIG_FILE);
  try {
    return JSON.parse(readFileSync(configPath, 'utf-8'));
  } catch {
    return null;
  }
}

function writeProjectConfig(tools, languages) {
  const configPath = join(process.cwd(), CONFIG_FILE);
  writeFileSync(configPath, JSON.stringify({ tools, languages }, null, 2) + '\n');
}

// ─── Content (fetched from orm.st at runtime) ───────────────────────────────

const SKILLS_BASE_URL = 'https://orm.st/skills';
const STORM_SKILL_MARKER = '<!-- storm-managed: storm-docs -->';

// --dev <dir> flag: read skills from a local directory instead of orm.st.
let devSkillsDir = null;
{
  const devIdx = process.argv.indexOf('--dev');
  if (devIdx !== -1 && process.argv[devIdx + 1]) {
    devSkillsDir = process.argv[devIdx + 1];
  }
}

async function fetchRules() {
  try {
    if (devSkillsDir) {
      return readFileSync(join(devSkillsDir, 'storm-rules.md'), 'utf-8');
    }
    const res = await fetch(`${SKILLS_BASE_URL}/storm-rules.md`);
    if (!res.ok) throw new Error(`${res.status}`);
    return await res.text();
  } catch {
    return null;
  }
}


async function fetchSkillIndex(language) {
  try {
    if (devSkillsDir) {
      return JSON.parse(readFileSync(join(devSkillsDir, `index-${language}.json`), 'utf-8'));
    }
    const res = await fetch(`${SKILLS_BASE_URL}/index-${language}.json`);
    if (!res.ok) throw new Error(`${res.status}`);
    return await res.json();
  } catch {
    return null;
  }
}

const DEV_SETUP_APPEND = `

## Development Mode

This project uses a local (unpublished) version of Storm. Add \`mavenLocal()\` as the first repository in Gradle so it resolves Storm artifacts from the local Maven cache (\`~/.m2/repository\`):

\`\`\`kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
\`\`\`
`;

async function fetchSkill(name) {
  try {
    if (devSkillsDir) {
      let content = readFileSync(join(devSkillsDir, `${name}.md`), 'utf-8');
      if (name === 'storm-setup') content = content.trimEnd() + '\n' + DEV_SETUP_APPEND;
      return content.trimEnd() + '\n\n' + STORM_SKILL_MARKER + '\n';
    }
    const url = `${SKILLS_BASE_URL}/${name}.md`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${res.status}`);
    const content = await res.text();
    return content.trimEnd() + '\n\n' + STORM_SKILL_MARKER + '\n';
  } catch {
    return null;
  }
}

function installSkill(name, content, toolConfig, created) {
  const cwd = process.cwd();
  const fullPath = join(cwd, toolConfig.skillPath(name));
  if (existsSync(fullPath) && readFileSync(fullPath, 'utf-8') === content) return;
  mkdirSync(dirname(fullPath), { recursive: true });
  writeFileSync(fullPath, content);
  created.push(toolConfig.skillPath(name));
}

function cleanStaleSkills(toolConfigs, installedSkillNames, skipped) {
  const cwd = process.cwd();
  const installed = new Set(installedSkillNames);

  for (const config of toolConfigs) {
    if (!config.skillDirs) continue;

    for (const dir of config.skillDirs) {
      const fullDir = join(cwd, dir);
      if (!existsSync(fullDir)) continue;

      let entries;
      try { entries = readdirSync(fullDir); } catch { continue; }

      for (const entry of entries) {
        // Check files directly in this directory.
        const filePath = join(fullDir, entry);
        const candidates = [];
        try {
          const stat = statSync(filePath);
          if (stat.isFile()) {
            candidates.push(filePath);
          } else if (stat.isDirectory()) {
            // Check nested SKILL.md (Claude/Windsurf skills layout).
            const nested = join(filePath, 'SKILL.md');
            if (existsSync(nested)) candidates.push(nested);
          }
        } catch { continue; }

        for (const candidate of candidates) {
          try {
            const content = readFileSync(candidate, 'utf-8');
            const isStormManaged = content.trimEnd().endsWith(STORM_SKILL_MARKER)
              || /^<!-- storm-managed: \S+ -->/.test(content);
            if (!isStormManaged) continue;

            // Derive skill name from path.
            const name = candidate.endsWith('SKILL.md')
              ? basename(dirname(candidate))
              : basename(candidate).replace(/\.(instructions\.)?md$/, '');
            if (!installed.has(name)) {
              unlinkSync(candidate);
              // Remove empty parent directory for nested layout.
              const parentDir = dirname(candidate);
              if (parentDir !== fullDir) {
                try {
                  const remaining = readdirSync(parentDir);
                  if (remaining.length === 0) rmdirSync(parentDir);
                } catch {}
              }
              skipped.push(`${name} (removed, no longer available)`);
            }
          } catch {}
        }
      }
    }
  }
}

// ─── Tool configs ────────────────────────────────────────────────────────────

const TOOL_CONFIGS = {
  claude: {
    name: 'Claude Code',
    rulesFile:  'CLAUDE.md',
    mcpFile:    '.mcp.json',
    mcpFormat:  'claude',
    skillPath:  (name) => `.claude/skills/${name}/SKILL.md`,
    skillDirs:  ['.claude/skills', '.claude/commands'],
  },
  cursor: {
    name: 'Cursor',
    rulesFile:  '.cursor/rules/storm.md',
    mcpFile:   '.cursor/mcp.json',
    mcpFormat: 'claude',
    skillPath: (name) => `.cursor/rules/${name}.md`,
    skillDirs: ['.cursor/rules'],
  },
  copilot: {
    name: 'GitHub Copilot',
    rulesFile:  '.github/copilot-instructions.md',
    skillPath: (name) => `.github/instructions/${name}.instructions.md`,
    skillDirs: ['.github/instructions'],
  },
  windsurf: {
    name: 'Windsurf',
    rulesFile:  '.windsurf/rules/storm.md',
    mcpFormat: 'windsurf',
    skillPath: (name) => `.windsurf/rules/${name}.md`,
    skillDirs: ['.windsurf/rules'],
  },
  codex: {
    name: 'Codex',
    rulesFile:  'AGENTS.md',
    mcpFormat: 'codex',
  },
};

// Schema rules fetched from orm.st/skills/storm-schema-rules.md at runtime.

const DIALECTS = {
  postgresql:  { name: 'PostgreSQL',  driver: 'pg',             defaultPort: 5432 },
  mysql:       { name: 'MySQL',       driver: 'mysql2',         defaultPort: 3306 },
  mariadb:     { name: 'MariaDB',     driver: 'mysql2',         defaultPort: 3306 },
  oracle:      { name: 'Oracle',      driver: 'oracledb',       defaultPort: 1521 },
  mssqlserver: { name: 'SQL Server',  driver: 'mssql',          defaultPort: 1433 },
  sqlite:      { name: 'SQLite',      driver: 'better-sqlite3', defaultPort: 0, fileBased: true },
  h2:          { name: 'H2',          driver: 'pg',             defaultPort: 5435 },
};

const MCP_SERVER_SOURCE = `#!/usr/bin/env node
// Storm Schema MCP Server
// Generated by storm cli v${VERSION}
//
// Exposes database schema metadata via MCP. Read-only, no data access.

import { createRequire } from 'module';
import { readFileSync } from 'fs';
import { dirname, join } from 'path';
import { createInterface } from 'readline';
import { fileURLToPath } from 'url';

var __dirname = dirname(fileURLToPath(import.meta.url));
var require = createRequire(import.meta.url);
var configPath = process.argv[2] || join(__dirname, 'connection.json');
var config = JSON.parse(readFileSync(configPath, 'utf-8'));

// ─── Database ────────────────────────────────────────────

var db, dbType;
var dbReady = connectDatabase();

async function connectDatabase() {
  if (config.dialect === 'postgresql' || config.dialect === 'h2') {
    var pg = require('pg');
    db = new pg.Pool({
      host: config.host, port: config.port, database: config.database,
      user: config.username, password: config.password,
    });
    dbType = 'pg';
  } else if (config.dialect === 'mysql' || config.dialect === 'mariadb') {
    var mysql = require('mysql2/promise');
    db = mysql.createPool({
      host: config.host, port: config.port, database: config.database,
      user: config.username, password: config.password,
    });
    dbType = 'mysql';
  } else if (config.dialect === 'mssqlserver') {
    var mssql = require('mssql');
    db = await mssql.connect({
      server: config.host, port: config.port, database: config.database,
      user: config.username, password: config.password,
      options: { encrypt: false, trustServerCertificate: true },
    });
    dbType = 'mssql';
  } else if (config.dialect === 'oracle') {
    var oracledb = require('oracledb');
    oracledb.outFormat = oracledb.OUT_FORMAT_OBJECT;
    db = await oracledb.getConnection({
      user: config.username, password: config.password,
      connectString: config.host + ':' + config.port + '/' + config.database,
    });
    dbType = 'oracle';
  } else if (config.dialect === 'sqlite') {
    var Database = require('better-sqlite3');
    db = new Database(config.database, { readonly: true });
    dbType = 'sqlite';
  }
}

async function dbQuery(sql, params) {
  await dbReady;
  if (dbType === 'pg') {
    var result = await db.query(sql, params);
    return result.rows;
  } else if (dbType === 'mysql') {
    var response = await db.execute(sql, params);
    return response[0];
  } else if (dbType === 'mssql') {
    var request = db.request();
    params.forEach(function(p, i) { request.input('p' + i, p); });
    var result = await request.query(sql);
    return result.recordset;
  } else if (dbType === 'oracle') {
    var result = await db.execute(sql, params);
    return result.rows;
  } else if (dbType === 'sqlite') {
    return params.length > 0 ? db.prepare(sql).all(params) : db.prepare(sql).all();
  }
}

var schemaName;
if (config.dialect === 'postgresql') schemaName = 'public';
else if (config.dialect === 'h2') schemaName = 'PUBLIC';
else if (config.dialect === 'mssqlserver') schemaName = 'dbo';
else if (config.dialect === 'oracle') schemaName = (config.username || '').toUpperCase();
else if (config.dialect !== 'sqlite') schemaName = config.database;

function ph(n) {
  if (dbType === 'pg') return '$' + n;
  if (dbType === 'mssql') return '@p' + (n - 1);
  if (dbType === 'oracle') return ':' + n;
  return '?';
}

// ─── Schema queries ──────────────────────────────────────

async function listTables() {
  await dbReady;
  if (dbType === 'sqlite') {
    return db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
      .all().map(function(r) { return r.name; });
  }
  if (dbType === 'oracle') {
    var rows = await dbQuery(
      'SELECT table_name FROM all_tables WHERE owner = ' + ph(1) + ' ORDER BY table_name',
      [schemaName]);
    return rows.map(function(r) { return r.TABLE_NAME; });
  }
  var sql = 'SELECT table_name FROM information_schema.tables'
    + ' WHERE table_schema = ' + ph(1)
    + " AND table_type = 'BASE TABLE'"
    + ' ORDER BY table_name';
  var rows = await dbQuery(sql, [schemaName]);
  return rows.map(function(r) { return r.table_name || r.TABLE_NAME; });
}

async function describeTable(tableName) {
  await dbReady;
  if (dbType === 'sqlite') return describeSqlite(tableName);
  if (dbType === 'oracle') return describeOracle(tableName);
  return describeInfoSchema(tableName);
}

function describeSqlite(tableName) {
  var quoted = '"' + tableName.replace(/"/g, '""') + '"';
  var columns = db.pragma('table_info(' + quoted + ')');
  var fks = db.pragma('foreign_key_list(' + quoted + ')');
  var fkMap = {};
  fks.forEach(function(fk) {
    fkMap[fk.from] = { referencedTable: fk.table, referencedColumn: fk.to };
  });
  return {
    table: tableName,
    columns: columns.map(function(c) {
      return {
        name: c.name, type: c.type, nullable: c.notnull === 0,
        defaultValue: c.dflt_value, primaryKey: c.pk > 0,
        foreignKey: fkMap[c.name] || null,
      };
    }),
  };
}

async function describeOracle(tableName) {
  var colSql = 'SELECT column_name, data_type, nullable, data_default,'
    + ' char_length, data_precision, data_scale'
    + ' FROM all_tab_columns WHERE owner = ' + ph(1) + ' AND table_name = ' + ph(2)
    + ' ORDER BY column_id';
  var pkSql = 'SELECT cols.column_name FROM all_constraints cons'
    + ' JOIN all_cons_columns cols ON cons.constraint_name = cols.constraint_name'
    + ' AND cons.owner = cols.owner'
    + ' WHERE cons.owner = ' + ph(1) + ' AND cons.table_name = ' + ph(2)
    + " AND cons.constraint_type = 'P' ORDER BY cols.position";
  var fkSql = 'SELECT cols.column_name, r_cols.table_name AS referenced_table,'
    + ' r_cols.column_name AS referenced_column FROM all_constraints cons'
    + ' JOIN all_cons_columns cols ON cons.constraint_name = cols.constraint_name'
    + ' AND cons.owner = cols.owner'
    + ' JOIN all_cons_columns r_cols ON cons.r_constraint_name = r_cols.constraint_name'
    + ' AND cons.r_owner = r_cols.owner'
    + ' WHERE cons.owner = ' + ph(1) + ' AND cons.table_name = ' + ph(2)
    + " AND cons.constraint_type = 'R'";
  var params = [schemaName, tableName];
  var columns = await dbQuery(colSql, params);
  var pks = await dbQuery(pkSql, params);
  var fks = await dbQuery(fkSql, params);
  var pkNames = pks.map(function(r) { return r.COLUMN_NAME; });
  var fkMap = {};
  fks.forEach(function(r) {
    fkMap[r.COLUMN_NAME] = {
      referencedTable: r.REFERENCED_TABLE, referencedColumn: r.REFERENCED_COLUMN,
    };
  });
  return {
    table: tableName,
    columns: columns.map(function(c) {
      return {
        name: c.COLUMN_NAME, type: c.DATA_TYPE,
        nullable: c.NULLABLE === 'Y', defaultValue: c.DATA_DEFAULT,
        primaryKey: pkNames.indexOf(c.COLUMN_NAME) >= 0,
        foreignKey: fkMap[c.COLUMN_NAME] || null,
      };
    }),
  };
}

async function describeInfoSchema(tableName) {
  var colSql = 'SELECT column_name, data_type, is_nullable, column_default,'
    + ' character_maximum_length, numeric_precision, numeric_scale'
    + ' FROM information_schema.columns'
    + ' WHERE table_schema = ' + ph(1) + ' AND table_name = ' + ph(2)
    + ' ORDER BY ordinal_position';
  var pkSql = 'SELECT kcu.column_name FROM information_schema.table_constraints tc'
    + ' JOIN information_schema.key_column_usage kcu'
    + '   ON tc.constraint_name = kcu.constraint_name'
    + '   AND tc.table_schema = kcu.table_schema'
    + ' WHERE tc.table_schema = ' + ph(1) + ' AND tc.table_name = ' + ph(2)
    + "   AND tc.constraint_type = 'PRIMARY KEY'"
    + ' ORDER BY kcu.ordinal_position';
  var fkSql;
  if (dbType === 'pg') {
    fkSql = 'SELECT kcu.column_name, ccu.table_name AS referenced_table,'
      + ' ccu.column_name AS referenced_column'
      + ' FROM information_schema.table_constraints tc'
      + ' JOIN information_schema.key_column_usage kcu'
      + '   ON tc.constraint_name = kcu.constraint_name'
      + '   AND tc.table_schema = kcu.table_schema'
      + ' JOIN information_schema.constraint_column_usage ccu'
      + '   ON tc.constraint_name = ccu.constraint_name'
      + '   AND tc.table_schema = ccu.table_schema'
      + ' WHERE tc.table_schema = ' + ph(1) + ' AND tc.table_name = ' + ph(2)
      + "   AND tc.constraint_type = 'FOREIGN KEY'";
  } else if (dbType === 'mssql') {
    fkSql = 'SELECT COL_NAME(fkc.parent_object_id, fkc.parent_column_id) AS column_name,'
      + ' OBJECT_NAME(fkc.referenced_object_id) AS referenced_table,'
      + ' COL_NAME(fkc.referenced_object_id, fkc.referenced_column_id) AS referenced_column'
      + ' FROM sys.foreign_key_columns fkc'
      + ' JOIN sys.tables t ON fkc.parent_object_id = t.object_id'
      + ' WHERE SCHEMA_NAME(t.schema_id) = ' + ph(1) + ' AND t.name = ' + ph(2);
  } else {
    fkSql = 'SELECT kcu.column_name,'
      + ' kcu.referenced_table_name AS referenced_table,'
      + ' kcu.referenced_column_name AS referenced_column'
      + ' FROM information_schema.key_column_usage kcu'
      + ' JOIN information_schema.table_constraints tc'
      + '   ON tc.constraint_name = kcu.constraint_name'
      + '   AND tc.table_schema = kcu.table_schema'
      + ' WHERE kcu.table_schema = ' + ph(1) + ' AND kcu.table_name = ' + ph(2)
      + "   AND tc.constraint_type = 'FOREIGN KEY'";
  }
  var params = [schemaName, tableName];
  var columns = await dbQuery(colSql, params);
  var pks = await dbQuery(pkSql, params);
  var fks = await dbQuery(fkSql, params);
  var pkNames = pks.map(function(r) { return r.column_name || r.COLUMN_NAME; });
  var fkMap = {};
  fks.forEach(function(r) {
    var col = r.column_name || r.COLUMN_NAME;
    fkMap[col] = {
      referencedTable: r.referenced_table || r.REFERENCED_TABLE,
      referencedColumn: r.referenced_column || r.REFERENCED_COLUMN,
    };
  });
  return {
    table: tableName,
    columns: columns.map(function(c) {
      var name = c.column_name || c.COLUMN_NAME;
      return {
        name: name, type: c.data_type || c.DATA_TYPE,
        nullable: (c.is_nullable || c.IS_NULLABLE) === 'YES',
        defaultValue: c.column_default || c.COLUMN_DEFAULT || null,
        primaryKey: pkNames.indexOf(name) >= 0,
        foreignKey: fkMap[name] || null,
      };
    }),
  };
}

// ─── MCP Protocol ────────────────────────────────────────

var TOOLS = [
  {
    name: 'list_tables',
    description: 'List all tables in the database.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'describe_table',
    description: 'Describe a table: columns, types, nullability, primary key, and foreign keys.',
    inputSchema: {
      type: 'object',
      properties: { table: { type: 'string', description: 'Table name' } },
      required: ['table'],
    },
  },
];

function respond(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: id, result: result }) + '\\n');
}

function respondError(id, code, message) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: id, error: { code: code, message: message } }) + '\\n');
}

var rl = createInterface({ input: process.stdin });

rl.on('line', async function(line) {
  var msg;
  try { msg = JSON.parse(line); } catch(e) { return; }

  if (msg.method === 'initialize') {
    respond(msg.id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'storm-schema', version: '${VERSION}' },
    });
  } else if (msg.method === 'notifications/initialized') {
    // no response
  } else if (msg.method === 'tools/list') {
    respond(msg.id, { tools: TOOLS });
  } else if (msg.method === 'tools/call') {
    try {
      var result;
      if (msg.params.name === 'list_tables') {
        result = await listTables();
      } else if (msg.params.name === 'describe_table') {
        result = await describeTable(msg.params.arguments.table);
      } else {
        respondError(msg.id, -32601, 'Unknown tool: ' + msg.params.name);
        return;
      }
      respond(msg.id, {
        content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
      });
    } catch(e) {
      respondError(msg.id, -32000, e.message);
    }
  } else if (msg.id) {
    respondError(msg.id, -32601, 'Method not found: ' + msg.method);
  }
});
`;

// ─── File helpers ────────────────────────────────────────────────────────────

const MARKER_START = '<!-- STORM:START -->';
const MARKER_END   = '<!-- STORM:END -->';

function installRulesBlock(filePath, content, created, appended) {
  const block = `${MARKER_START}\n${content.trim()}\n${MARKER_END}`;
  mkdirSync(dirname(filePath), { recursive: true });

  if (!existsSync(filePath)) {
    writeFileSync(filePath, block + '\n');
    created.push(filePath.replace(process.cwd() + '/', ''));
    return;
  }

  const existing = readFileSync(filePath, 'utf-8');
  const startIdx = existing.indexOf(MARKER_START);
  const endIdx   = existing.indexOf(MARKER_END);

  if (startIdx !== -1 && endIdx !== -1) {
    // Replace existing block.
    const updated = existing.substring(0, startIdx) + block + existing.substring(endIdx + MARKER_END.length);
    writeFileSync(filePath, updated);
    appended.push(filePath.replace(process.cwd() + '/', ''));
  } else {
    // Append new block.
    appendFileSync(filePath, '\n\n' + block + '\n');
    appended.push(filePath.replace(process.cwd() + '/', ''));
  }
}

function registerMcp(toolConfig, stormDir, created, appended) {
  const cwd = process.cwd();
  const serverPath = join(stormDir, 'mcp-server.mjs');

  if (toolConfig.mcpFormat === 'codex') {
    // Codex uses TOML in .codex/config.toml
    const tomlPath = join(cwd, '.codex', 'config.toml');
    const tomlEntry = '\n[mcp_servers.storm-schema]\n'
      + 'type = "stdio"\n'
      + 'command = "node"\n'
      + 'args = ["' + serverPath + '"]\n';
    if (existsSync(tomlPath)) {
      const existing = readFileSync(tomlPath, 'utf-8');
      if (!existing.includes('storm-schema')) {
        appendFileSync(tomlPath, tomlEntry);
        appended.push('.codex/config.toml');
      }
    } else {
      mkdirSync(dirname(tomlPath), { recursive: true });
      writeFileSync(tomlPath, tomlEntry.trimStart());
      created.push('.codex/config.toml');
    }
    return;
  }

  if (toolConfig.mcpFormat === 'windsurf') {
    // Windsurf has no project-level MCP config; handled in summary output.
    return;
  }

  // Claude, Cursor: JSON with mcpServers key.
  if (!toolConfig.mcpFile) return;
  const mcpPath = join(cwd, toolConfig.mcpFile);
  const isNew = !existsSync(mcpPath);
  let mcpConfig = {};
  if (!isNew) {
    try { mcpConfig = JSON.parse(readFileSync(mcpPath, 'utf-8')); } catch {}
  }
  if (!mcpConfig.mcpServers) mcpConfig.mcpServers = {};
  if (mcpConfig.mcpServers['storm-schema']) return; // already registered

  mcpConfig.mcpServers['storm-schema'] = {
    type: 'stdio',
    command: 'node',
    args: [serverPath],
  };
  mkdirSync(dirname(mcpPath), { recursive: true });
  writeFileSync(mcpPath, JSON.stringify(mcpConfig, null, 2) + '\n');
  (isNew ? created : appended).push(toolConfig.mcpFile);
}


// ─── Database setup ──────────────────────────────────────────────────────────

async function setupDatabase(preConfigured) {
  const stormDir = join(homedir(), '.storm');
  const connectionPath = join(stormDir, 'connection.json');

  let connection;

  if (preConfigured) {
    // Non-interactive: use the provided connection details directly.
    connection = preConfigured;
  } else {
    // Interactive: prompt the user.
    if (existsSync(connectionPath)) {
      try {
        const existing = JSON.parse(readFileSync(connectionPath, 'utf-8'));
        console.log(dimText(`  Current connection: ${existing.dialect}://${existing.host || ''}${existing.port ? ':' + existing.port : ''}/${existing.database}`));
        console.log();
        const reconfigure = await confirm({ message: 'Reconfigure?', defaultValue: false });
        if (!reconfigure) return stormDir;
        console.log();
      } catch {}
    }

    const dialect = await select({
      message: 'Database dialect',
      choices: [
        { name: 'PostgreSQL',  value: 'postgresql' },
        { name: 'MySQL',       value: 'mysql' },
        { name: 'MariaDB',     value: 'mariadb' },
        { name: 'Oracle',      value: 'oracle' },
        { name: 'SQL Server',  value: 'mssqlserver' },
        { name: 'SQLite',      value: 'sqlite' },
        { name: 'H2',          value: 'h2' },
      ],
    });

    const dialectConfig = DIALECTS[dialect];

    if (dialectConfig.fileBased) {
      const database = await textInput({ message: 'Database file path' });
      if (!database) {
        console.log(boltYellow('\n  Database file path is required.'));
        return null;
      }
      connection = { dialect, database };
    } else {
      if (dialect === 'h2') {
        console.log(dimText('  Note: Requires H2 running with PG wire protocol (-pgPort)'));
      }
      if (dialect === 'oracle') {
        console.log(dimText('  Note: Uses oracledb thin mode (no Oracle Client required)'));
      }

      const defaultPort = String(dialectConfig.defaultPort);
      const host     = await textInput({ message: 'Host',     defaultValue: 'localhost' });
      const port     = await textInput({ message: 'Port',     defaultValue: defaultPort });
      const database = await textInput({ message: 'Database' });
      const username = await textInput({ message: 'Username', defaultValue: 'storm' });
      const password = await textInput({ message: 'Password', mask: true });

      if (!database) {
        console.log(boltYellow('\n  Database name is required.'));
        return null;
      }

      connection = {
        dialect,
        host: host || 'localhost',
        port: parseInt(port || defaultPort, 10),
        database,
        username: username || 'storm',
        password: password || '',
      };
    }
  }

  // Install driver.
  const driverPackage = DIALECTS[connection.dialect].driver;
  console.log();
  console.log(dimText(`  Installing ${DIALECTS[connection.dialect].name} driver...`));

  mkdirSync(stormDir, { recursive: true });

  const packageJsonPath = join(stormDir, 'package.json');
  if (!existsSync(packageJsonPath)) {
    writeFileSync(packageJsonPath, '{"private":true}\n');
  }

  try {
    execSync(`npm install ${driverPackage} --prefix "${stormDir}"`, {
      stdio: 'pipe',
      timeout: 60000,
    });
  } catch (error) {
    console.log(boltYellow('  Failed to install driver. Make sure npm is available.'));
    console.log(dimText('  ' + (error.stderr?.toString().trim() || error.message)));
    return null;
  }

  // Write connection config and MCP server.
  writeFileSync(connectionPath, JSON.stringify(connection, null, 2) + '\n');
  writeFileSync(join(stormDir, 'mcp-server.mjs'), MCP_SERVER_SOURCE);

  return stormDir;
}

// ─── Update (non-interactive) ────────────────────────────────────────────────

function detectConfiguredTools() {
  const cwd = process.cwd();
  const tools = [];
  for (const [id, config] of Object.entries(TOOL_CONFIGS)) {
    // A tool is "configured" if its rules file or any skill directory exists.
    if (config.rulesFile && existsSync(join(cwd, config.rulesFile))) {
      tools.push(id);
    } else if (config.skillDirs) {
      for (const dir of config.skillDirs) {
        if (existsSync(join(cwd, dir))) { tools.push(id); break; }
      }
    }
  }
  return tools;
}

function detectConfiguredLanguages(toolConfigs) {
  const cwd = process.cwd();
  const languages = new Set();
  for (const config of toolConfigs) {
    if (!config.skillDirs) continue;
    for (const dir of config.skillDirs) {
      const fullDir = join(cwd, dir);
      if (!existsSync(fullDir)) continue;
      let entries;
      try { entries = readdirSync(fullDir); } catch { continue; }
      for (const entry of entries) {
        // Check storm-managed skill markers for language suffixes.
        const filePath = join(fullDir, entry);
        const candidates = [];
        try {
          const stat = statSync(filePath);
          if (stat.isFile()) candidates.push(filePath);
          else if (stat.isDirectory()) {
            const nested = join(filePath, 'SKILL.md');
            if (existsSync(nested)) candidates.push(nested);
          }
        } catch { continue; }
        for (const candidate of candidates) {
          try {
            const content = readFileSync(candidate, 'utf-8');
            const match = content.match(/^<!-- storm-managed: (\S+) -->/);
            if (match) {
              const name = match[1];
              if (name.endsWith('-kotlin')) languages.add('kotlin');
              if (name.endsWith('-java')) languages.add('java');
            }
          } catch {}
        }
      }
    }
  }
  return [...languages];
}

async function update() {
  const projectConfig = readProjectConfig();

  const tools = projectConfig?.tools ?? detectConfiguredTools();
  if (tools.length === 0) {
    console.log(boltYellow('\n  No configured AI tools found. Run `storm init` first.\n'));
    return;
  }

  const toolConfigs = tools.map(t => TOOL_CONFIGS[t]);
  const skillToolConfigs = toolConfigs.filter(c => c.skillPath);

  // Use persisted language choice, fall back to detection from skill markers, then both.
  let languages = projectConfig?.languages;
  if (!languages || languages.length === 0) {
    languages = detectConfiguredLanguages(skillToolConfigs);
  }
  if (languages.length === 0) {
    languages = ['kotlin', 'java'];
  }

  console.log(dimText(`  Tools:     ${tools.map(t => TOOL_CONFIGS[t].name).join(', ')}`));
  console.log(dimText(`  Languages: ${languages.join(', ')}`));
  console.log();

  // Fetch rules and skill indexes.
  console.log(dimText(`  Fetching content from ${devSkillsDir ? devSkillsDir : 'https://orm.st'}...`));
  const fetchPromises = [fetchRules(), ...languages.map(l => fetchSkillIndex(l))];
  const [stormRules, ...skillIndexes] = await Promise.all(fetchPromises);
  if (!stormRules) {
    console.log(boltYellow('\n  Could not fetch Storm rules from https://orm.st. Check your connection.\n'));
    return;
  }

  const created  = [];
  const appended = [];
  const skipped  = [];

  // Update rules blocks.
  for (const toolId of tools) {
    const config = TOOL_CONFIGS[toolId];
    if (!config || !config.rulesFile) continue;
    installRulesBlock(join(process.cwd(), config.rulesFile), stormRules, created, appended);
  }

  // Merge skill lists from all selected languages (deduplicated).
  const skillNames = [...new Set(skillIndexes.flatMap(idx => idx?.skills ?? []))];
  const schemaSkillNames = [...new Set(skillIndexes.flatMap(idx => idx?.schemaSkills ?? []))];
  const installedSkillNames = [];

  // Fetch and install skills.
  if (skillToolConfigs.length > 0) {
    console.log(dimText(`  Fetching skills from ${devSkillsDir ? devSkillsDir : 'https://orm.st'}...`));
    const fetchedSkills = new Map();
    for (const skillName of skillNames) {
      const content = await fetchSkill(skillName);
      if (!content) { skipped.push(skillName + ' (fetch failed)'); continue; }
      fetchedSkills.set(skillName, content);
      installedSkillNames.push(skillName);
    }

    for (const config of skillToolConfigs) {
      for (const [name, content] of fetchedSkills) {
        installSkill(name, content, config, created);
      }
    }

    // Also update schema-dependent skills if database is configured.
    const stormDir = join(homedir(), '.storm');
    const connectionPath = join(stormDir, 'connection.json');
    if (existsSync(connectionPath)) {
      const schemaRules = await fetchSkill('storm-schema-rules');
      for (const toolId of tools) {
        const config = TOOL_CONFIGS[toolId];
        if (config.rulesFile && schemaRules) {
          const rulesPath = join(process.cwd(), config.rulesFile);
          if (existsSync(rulesPath)) {
            const existing = readFileSync(rulesPath, 'utf-8');
            if (!existing.includes('Database Schema Access')) {
              const endMarker = existing.indexOf(MARKER_END);
              if (endMarker !== -1) {
                const updated = existing.substring(0, endMarker) + '\n' + schemaRules.replace('\n' + STORM_SKILL_MARKER, '') + '\n' + existing.substring(endMarker);
                writeFileSync(rulesPath, updated);
                if (!appended.includes(config.rulesFile)) appended.push(config.rulesFile);
              }
            }
          }
        }
      }

      for (const skillName of schemaSkillNames) {
        const content = await fetchSkill(skillName);
        if (!content) { skipped.push(skillName + ' (fetch failed)'); continue; }
        installedSkillNames.push(skillName);
        for (const config of skillToolConfigs) {
          installSkill(skillName, content, config, created);
        }
      }
    }

    // Remove stale skills.
    cleanStaleSkills(skillToolConfigs, installedSkillNames, skipped);
  }

  const uniqueCreated  = [...new Set(created)];
  const uniqueAppended = [...new Set(appended)];

  console.log();
  if (uniqueCreated.length > 0) {
    console.log(boltYellow('  Created:'));
    uniqueCreated.forEach(f => console.log(boltYellow(`    + ${f}`)));
  }
  if (uniqueAppended.length > 0) {
    console.log(boltYellow('  Updated:'));
    uniqueAppended.forEach(f => console.log(boltYellow(`    ~ ${f}`)));
  }
  if (skipped.length > 0) {
    console.log(dimText('  Skipped:'));
    skipped.forEach(f => console.log(dimText(`    - ${f}`)));
  }
  console.log();
  console.log(bold('  Skills and rules updated.'));
  console.log();
}

// ─── MCP (non-interactive re-register) ──────────────────────────────────────

async function updateMcp() {
  const stormDir = join(homedir(), '.storm');
  const connectionPath = join(stormDir, 'connection.json');

  if (!existsSync(connectionPath)) {
    // No existing connection; fall back to interactive setup.
    console.log(dimText('  No existing database connection found. Starting setup...'));
    console.log();
    const result = await setupDatabase();
    if (!result) return;
  }

  // Re-register MCP for all configured tools.
  const tools = detectConfiguredTools();
  if (tools.length === 0) {
    console.log(boltYellow('\n  No configured AI tools found. Run `storm init` first.\n'));
    return;
  }

  const created = [];
  const appended = [];
  for (const toolId of tools) {
    const config = TOOL_CONFIGS[toolId];
    if (!config.mcpFormat) continue;
    if (config.mcpFormat === 'codex' && existsSync(join(process.cwd(), '.codex'))) {
      registerMcp(config, stormDir, created, appended);
    } else if (config.mcpFile) {
      registerMcp(config, stormDir, created, appended);
    }
  }

  console.log();
  if (created.length > 0) {
    console.log(boltYellow('  Created:'));
    created.forEach(f => console.log(boltYellow(`    + ${f}`)));
  }
  if (appended.length > 0) {
    console.log(boltYellow('  Updated:'));
    appended.forEach(f => console.log(boltYellow(`    ~ ${f}`)));
  }
  if (created.length > 0 || appended.length > 0) {
    console.log();
    console.log(bold('  MCP configuration updated.'));
  } else {
    const toolNames = tools.map(t => TOOL_CONFIGS[t]?.name).filter(Boolean).join(', ');
    console.log(dimText(`  MCP already configured for ${toolNames}. No changes needed.`));
  }
  console.log();
}

// ─── Main flow ───────────────────────────────────────────────────────────────

async function setup() {
  await printWelcome();

  // Step 1: Select AI tools.
  console.log('  Storm installs rules and skills for your AI coding tools.');
  console.log('  Select the tools you use in this project.');
  console.log();
  const tools = await checkbox({
    message: 'Which AI tools do you use?',
    choices: [
      { name: 'Claude Code',     value: 'claude',   checked: true },
      { name: 'Cursor',          value: 'cursor' },
      { name: 'GitHub Copilot',  value: 'copilot' },
      { name: 'Windsurf',        value: 'windsurf' },
      { name: 'Codex',           value: 'codex' },
    ],
  });

  if (tools.length === 0) {
    console.log(boltYellow('\nNo tools selected. Run again when ready.'));
    return;
  }

  // Step 1b: Select language(s).
  console.log();
  console.log('  Storm provides language-specific skills for entity creation,');
  console.log('  repositories, queries, and SQL templates.');
  console.log();
  const languages = await checkbox({
    message: 'Which language(s) does this project use?',
    choices: [
      { name: 'Kotlin',                                  value: 'kotlin', checked: true },
      { name: 'Java (requires --enable-preview on JDK 21)', value: 'java' },
    ],
  });

  if (languages.length === 0) {
    console.log(boltYellow('\nNo language selected. Run again when ready.'));
    return;
  }

  // Persist tool and language choices so `storm update` can use them.
  writeProjectConfig(tools, languages);

  // Add .storm.json to .gitignore (machine-specific config).
  const gitignorePath = join(process.cwd(), '.gitignore');
  let gitignore = existsSync(gitignorePath) ? readFileSync(gitignorePath, 'utf-8') : '';
  if (!gitignore.includes(CONFIG_FILE)) {
    appendFileSync(gitignorePath, `\n# Storm config (machine-specific)\n${CONFIG_FILE}\n`);
  }

  console.log();

  // Step 2: Fetch and install rules.
  const created  = [];
  const appended = [];
  const skipped  = [];

  console.log(dimText(`  Fetching content from ${devSkillsDir ? devSkillsDir : 'https://orm.st'}...`));
  const fetchPromises = [fetchRules(), ...languages.map(l => fetchSkillIndex(l))];
  const [stormRules, ...skillIndexes] = await Promise.all(fetchPromises);
  if (!stormRules) {
    console.log(boltYellow('\n  Could not fetch Storm rules from https://orm.st. Check your connection.'));
    return;
  }

  // Merge skill lists from all selected languages (deduplicated).
  const skillNames = [...new Set(skillIndexes.flatMap(idx => idx?.skills ?? []))];
  const schemaSkillNames = [...new Set(skillIndexes.flatMap(idx => idx?.schemaSkills ?? []))];

  for (const toolId of tools) {
    const config = TOOL_CONFIGS[toolId];
    if (!config || !config.rulesFile) continue;
    installRulesBlock(join(process.cwd(), config.rulesFile), stormRules, created, appended);
  }

  // Step 3: Fetch and install skills for tools that support per-topic files.
  const skillToolConfigs = tools.map(t => TOOL_CONFIGS[t]).filter(c => c.skillPath);
  const installedSkillNames = [];
  const fetchedSkills = new Map();

  if (skillToolConfigs.length > 0) {
    console.log(dimText(`  Fetching skills from ${devSkillsDir ? devSkillsDir : 'https://orm.st'}...`));
    for (const skillName of skillNames) {
      const content = await fetchSkill(skillName);
      if (!content) { skipped.push(skillName + ' (fetch failed)'); continue; }
      fetchedSkills.set(skillName, content);
      installedSkillNames.push(skillName);
    }

    // Install fetched skills into each tool's directory.
    for (const config of skillToolConfigs) {
      for (const [name, content] of fetchedSkills) {
        installSkill(name, content, config, created);
      }
    }
  }

  // Step 4: Optional database connection.
  const mcpTools = tools.filter(t => TOOL_CONFIGS[t].mcpFormat);
  let dbConfigured = false;
  let stormDir = null;

  if (mcpTools.length > 0) {
    console.log();
    console.log('  Storm can connect to your local development database so AI tools');
    console.log('  can read your schema (tables, columns, foreign keys) and generate');
    console.log('  entities automatically. Credentials are stored locally and never');
    console.log('  exposed to the AI.');
    console.log();
    const connectDb = await confirm({
      message: 'Connect to a local database?',
      defaultValue: true,
    });

    if (connectDb) {
      console.log();
      stormDir = await setupDatabase();
      dbConfigured = stormDir !== null;
    }
  }

  // Step 5: Register MCP, append schema rules, update .gitignore, and install schema skills.
  if (dbConfigured) {
    // Add MCP config files to .gitignore (they contain machine-specific paths).
    const gitignorePath = join(process.cwd(), '.gitignore');
    const mcpIgnoreEntries = [];
    for (const toolId of tools) {
      const config = TOOL_CONFIGS[toolId];
      if (config.mcpFile) mcpIgnoreEntries.push(config.mcpFile);
    }
    if (mcpIgnoreEntries.length > 0) {
      let gitignore = existsSync(gitignorePath) ? readFileSync(gitignorePath, 'utf-8') : '';
      const missing = mcpIgnoreEntries.filter(e => !gitignore.includes(e));
      if (missing.length > 0) {
        const block = '\n# Storm MCP (machine-specific paths)\n' + missing.join('\n') + '\n';
        appendFileSync(gitignorePath, block);
        appended.push('.gitignore');
      }
    }
    // Fetch schema rules and append to each tool's rules block.
    const schemaRules = await fetchSkill('storm-schema-rules');
    for (const toolId of tools) {
      const config = TOOL_CONFIGS[toolId];
      registerMcp(config, stormDir, created, appended);
      if (config.rulesFile && schemaRules) {
        const rulesPath = join(process.cwd(), config.rulesFile);
        if (existsSync(rulesPath)) {
          const existing = readFileSync(rulesPath, 'utf-8');
          // Insert schema rules inside the STORM block if not already present.
          if (!existing.includes('Database Schema Access')) {
            const endMarker = existing.indexOf(MARKER_END);
            if (endMarker !== -1) {
              const updated = existing.substring(0, endMarker) + '\n' + schemaRules.replace('\n' + STORM_SKILL_MARKER, '') + '\n' + existing.substring(endMarker);
              writeFileSync(rulesPath, updated);
              appended.push(config.rulesFile);
            }
          }
        }
      }
    }

    // Fetch and install schema-dependent skills.
    if (skillToolConfigs.length > 0) {
      for (const skillName of schemaSkillNames) {
        const content = await fetchSkill(skillName);
        if (!content) { skipped.push(skillName + ' (fetch failed)'); continue; }
        installedSkillNames.push(skillName);
        for (const config of skillToolConfigs) {
          installSkill(skillName, content, config, created);
        }
      }
    }
  }

  // Step 6: Remove stale Storm-managed skills.
  if (skillToolConfigs.length > 0) {
    cleanStaleSkills(skillToolConfigs, installedSkillNames, skipped);
  }

  const uniqueCreated  = [...new Set(created)];
  const uniqueAppended = [...new Set(appended)];

  console.log();
  if (uniqueCreated.length > 0) {
    console.log(boltYellow('  Created:'));
    uniqueCreated.forEach(f => console.log(boltYellow(`    + ${f}`)));
  }
  if (uniqueAppended.length > 0) {
    console.log(boltYellow('  Updated:'));
    uniqueAppended.forEach(f => console.log(boltYellow(`    ~ ${f}`)));
  }
  if (skipped.length > 0) {
    console.log(dimText('  Skipped:'));
    skipped.forEach(f => console.log(dimText(`    - ${f}`)));
  }
  if (dbConfigured && tools.includes('windsurf')) {
    console.log();
    console.log(dimText('  Note: Windsurf requires manual MCP configuration.'));
    console.log(dimText(`  Add storm-schema server in Windsurf settings with command:`));
    console.log(dimText(`    node ${join(stormDir, 'mcp-server.mjs')}`));
  }

  console.log();
  console.log(bold("  You're all set!"));
  console.log();
  if (dbConfigured) {
    console.log(`  ${boltYellow('Quick start:')} Ask your AI tool to inspect the database schema or generate entities.`);
  } else {
    console.log(`  ${boltYellow('Quick start:')} Your AI tools now know Storm's patterns and conventions.`);
  }
  console.log(`  ${dimText('Learn more:')}   https://orm.st/ai`);
  console.log();
}

// ─── Demo ─────────────────────────────────────────────────────────────────────

async function demo() {
  await printWelcome(DEMO_TEXT_LINES);

  // Step 1: Select AI tool.
  console.log('  Storm Demo creates a Kotlin project in this empty directory and');
  console.log('  installs a skill that guides your AI tool to build a web application');
  console.log('  demonstrating Storm ORM using the public IMDB dataset.');
  console.log();
  const tool = await select({
    message: 'Which AI tool will you use?',
    choices: [
      { name: 'Claude Code',     value: 'claude' },
      { name: 'Cursor',          value: 'cursor' },
      { name: 'GitHub Copilot',  value: 'copilot' },
      { name: 'Windsurf',        value: 'windsurf' },
    ],
  });

  // Step 2: Check if directory is empty (ignoring hidden files/directories).
  const cwd = process.cwd();
  const entries = readdirSync(cwd).filter(e => !e.startsWith('.'));
  if (entries.length > 0) {
    console.log();
    console.log(boltYellow('  This directory is not empty.'));
    console.log(dimText('  Storm Demo requires an empty directory. Create a new directory and'));
    console.log(dimText('  run storm demo from there.'));
    console.log();
    return;
  }

  const tools = [tool];
  const config = TOOL_CONFIGS[tool];
  const created = [];
  const appended = [];
  const skipped = [];

  // Step 3: Fetch rules, Kotlin skill index, and demo skill index.
  console.log();
  console.log(dimText(`  Fetching content from ${devSkillsDir ? devSkillsDir : 'https://orm.st'}...`));
  const [stormRules, skillIndex, demoIndex] = await Promise.all([
    fetchRules(),
    fetchSkillIndex('kotlin'),
    fetchSkillIndex('demo'),
  ]);

  if (!stormRules) {
    console.log(boltYellow('\n  Could not fetch Storm rules from https://orm.st. Check your connection.'));
    return;
  }

  const skillNames = skillIndex?.skills ?? [];
  const demoSkillNames = demoIndex?.skills ?? [];

  // Step 4: Install rules block.
  if (config.rulesFile) {
    installRulesBlock(join(cwd, config.rulesFile), stormRules, created, appended);
  }

  // Step 5: Fetch and install Kotlin skills + demo skills.
  const installedSkillNames = [];
  if (config.skillPath) {
    console.log(dimText(`  Fetching skills from ${devSkillsDir ? devSkillsDir : 'https://orm.st'}...`));
    const fetchedSkills = new Map();
    for (const skillName of [...skillNames, ...demoSkillNames]) {
      const content = await fetchSkill(skillName);
      if (!content) { skipped.push(skillName + ' (fetch failed)'); continue; }
      fetchedSkills.set(skillName, content);
      installedSkillNames.push(skillName);
    }
    for (const [name, content] of fetchedSkills) {
      installSkill(name, content, config, created);
    }
  }

  // Step 6: Choose database and set up MCP server.
  const dialect = await select({
    message: 'Which database?',
    choices: [
      { name: 'PostgreSQL',  value: 'postgresql' },
      { name: 'MySQL',       value: 'mysql' },
      { name: 'MariaDB',     value: 'mariadb' },
      { name: 'Oracle',      value: 'oracle' },
      { name: 'SQL Server',  value: 'mssqlserver' },
      { name: 'SQLite',      value: 'sqlite' },
      { name: 'H2',          value: 'h2' },
    ],
  });
  const dialectInfo = DIALECTS[dialect] || DIALECTS.postgresql;
  let demoConnection;
  if (dialectInfo.fileBased) {
    demoConnection = { dialect, database: 'imdb.db' };
  } else {
    demoConnection = {
      dialect,
      host: 'localhost',
      port: dialectInfo.defaultPort,
      database: 'imdb',
      username: 'storm',
      password: 'storm',
    };
  }

  console.log(dimText('  Setting up MCP server for database schema access...'));
  const stormDir = await setupDatabase(demoConnection);
  const dbConfigured = stormDir !== null;

  if (dbConfigured) {
    // Register MCP for the selected tool.
    registerMcp(config, stormDir, created, appended);

    // Add MCP config file to .gitignore.
    if (config.mcpFile) {
      const gitignorePath = join(cwd, '.gitignore');
      let gitignore = existsSync(gitignorePath) ? readFileSync(gitignorePath, 'utf-8') : '';
      if (!gitignore.includes(config.mcpFile)) {
        appendFileSync(gitignorePath, `\n# Storm MCP (machine-specific paths)\n${config.mcpFile}\n`);
      }
    }

    // Fetch and install schema rules into the rules block.
    const schemaRules = await fetchSkill('storm-schema-rules');
    if (config.rulesFile && schemaRules) {
      const rulesPath = join(cwd, config.rulesFile);
      if (existsSync(rulesPath)) {
        const existing = readFileSync(rulesPath, 'utf-8');
        if (!existing.includes('Database Schema Access')) {
          const endMarker = existing.indexOf(MARKER_END);
          if (endMarker !== -1) {
            const updated = existing.substring(0, endMarker) + '\n' + schemaRules.replace('\n' + STORM_SKILL_MARKER, '') + '\n' + existing.substring(endMarker);
            writeFileSync(rulesPath, updated);
            appended.push(config.rulesFile);
          }
        }
      }
    }

    // Fetch and install schema-dependent skills.
    const schemaSkillNames = skillIndex?.schemaSkills ?? [];
    if (config.skillPath) {
      for (const skillName of schemaSkillNames) {
        const content = await fetchSkill(skillName);
        if (!content) { skipped.push(skillName + ' (fetch failed)'); continue; }
        installSkill(skillName, content, config, created);
        installedSkillNames.push(skillName);
      }
    }
  }

  // Step 7: Write project config and .gitignore.
  writeProjectConfig(tools, ['kotlin']);

  const gitignorePath = join(cwd, '.gitignore');
  if (!existsSync(gitignorePath)) {
    writeFileSync(gitignorePath, `# Storm config (machine-specific)\n${CONFIG_FILE}\n`);
    created.push('.gitignore');
  } else {
    let gitignore = readFileSync(gitignorePath, 'utf-8');
    if (!gitignore.includes(CONFIG_FILE)) {
      appendFileSync(gitignorePath, `\n# Storm config (machine-specific)\n${CONFIG_FILE}\n`);
    }
  }

  // Step 8: Clean stale skills.
  if (config.skillPath) {
    cleanStaleSkills([config], installedSkillNames, skipped);
  }

  // Summary.
  const uniqueCreated  = [...new Set(created)];
  const uniqueAppended = [...new Set(appended)];

  console.log();
  if (uniqueCreated.length > 0) {
    console.log(boltYellow('  Created:'));
    uniqueCreated.forEach(f => console.log(boltYellow(`    + ${f}`)));
  }
  if (uniqueAppended.length > 0) {
    console.log(boltYellow('  Updated:'));
    uniqueAppended.forEach(f => console.log(boltYellow(`    ~ ${f}`)));
  }
  if (skipped.length > 0) {
    console.log(dimText('  Skipped:'));
    skipped.forEach(f => console.log(dimText(`    - ${f}`)));
  }

  // Instructions.
  console.log();
  console.log(bold("  You're all set!"));
  console.log();
  if (tool === 'claude') {
    console.log(`  Start ${boltYellow('Claude Code')} in this directory and type:`);
    console.log();
    console.log(`    ${bold('/storm-demo')}`);
  } else {
    console.log(`  Open this directory in ${boltYellow(config.name)} and ask:`);
    console.log();
    console.log(`    ${bold('Run the Storm demo')}`);
  }
  console.log();
}

// ─── Entry ───────────────────────────────────────────────────────────────────

async function run() {
  const args = process.argv.slice(2);
  const command = args.find(a => !a.startsWith('-'));

  if (args.includes('--help') || args.includes('-h')) {
    console.log(`
  ${bold('storm')} - Configure AI coding assistants for Storm ORM

  ${dimText('Usage:')}
    storm init               Configure rules, skills, and database (default)
    storm demo               Create a demo project in an empty directory
    storm update             Update rules and skills (non-interactive)
    storm mcp                Re-register MCP servers for configured tools

  ${dimText('Options:')}
    --help, -h               Show this help message
    --version, -v            Show version
    --dev <dir>              Read skills from a local directory instead of orm.st

  ${dimText('Learn more:')}  https://orm.st/ai
`);
    return;
  }

  if (args.includes('--version') || args.includes('-v')) {
    console.log(VERSION);
    return;
  }

  if (command === 'update') {
    await update();
  } else if (command === 'mcp') {
    await updateMcp();
  } else if (command === 'demo') {
    await demo();
  } else {
    await setup();
  }
}

run().catch(error => {
  if (error.name === 'ExitPromptError') process.exit(0);
  console.error(error);
  process.exit(1);
});
