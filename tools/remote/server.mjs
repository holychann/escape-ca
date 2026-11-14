// 퍼즐 리모컨: a small local page that sits next to the emulator and does with buttons what tools/vc.mjs does with
// commands. It also starts the emulator, the story server and the app, so a test needs no terminal.
// Run with 퍼즐리모컨.bat, or: node tools/remote/server.mjs  (then open http://127.0.0.1:8790)
import { execFile, spawn } from 'node:child_process';
import { existsSync, openSync, readdirSync, readFileSync, renameSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SDK, WIN, adbAsync, connect, seatName, sleep } from '../vc-lib.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const PORT = 8790;
const PKG = 'kr.escape.car';
// Windows builds into C:/dev/build (see car/app/build.gradle.kts); elsewhere Gradle's default car/app/build
const APK = WIN
  ? 'C:/dev/build/escape-car/app/outputs/apk/debug/app-debug.apk'
  : join(ROOT, 'car', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const EMULATOR = join(SDK, 'emulator', WIN ? 'emulator.exe' : 'emulator');
const EMULATOR_PROCS = ['qemu-system-x86_64.exe', 'emulator.exe'];
const PYTHON = WIN ? join(ROOT, 'server', '.venv', 'Scripts', 'python.exe') : join(ROOT, 'server', '.venv', 'bin', 'python');
const STORY_LOG = WIN ? 'C:/dev/build/story-server.log' : join(tmpdir(), 'escape-car-story-server.log');
// macOS/Linux: the emulator and its qemu child both carry "-avd pleos" on their command line
const AVD_MATCH = '-avd pleos';

const logs = [];
const log = (m) => {
  logs.push(`${new Date().toTimeString().slice(0, 8)} ${m}`);
  if (logs.length > 60) logs.shift();
};

// one console connection for the whole run: its session signal is what lets console changes reach the app
let vc = null;
async function car() {
  if (!vc) vc = await connect({ log });
  return vc;
}
function dropCar() {
  vc?.close();
  vc = null;
}

// null when adb gave no answer in time, so a frozen emulator can be told apart from an empty reply
async function quiet(args, timeout = 5000) {
  try {
    return (await adbAsync(args, timeout)).trim();
  } catch {
    return null;
  }
}

const run = (cmd, args) =>
  new Promise((ok) => execFile(cmd, args, { encoding: 'utf8', timeout: 10_000, windowsHide: true }, (_e, out) => ok(out || '')));
const emulatorRunning = async () =>
  WIN
    ? (await run('tasklist', ['/FI', `IMAGENAME eq ${EMULATOR_PROCS[0]}`, '/NH'])).includes(EMULATOR_PROCS[0])
    : (await run('pgrep', ['-f', '--', AVD_MATCH])).trim() !== '';

// The emulator can freeze while adb still lists it, and then every shell command just hangs.
// Three unanswered checks in a row (about 15 s) count as frozen rather than slow.
let emu = 'off';
let silentChecks = 0;
async function emulatorState() {
  if ((await quiet(['get-state'], 3000)) !== 'device') {
    silentChecks = 0;
    return (emu = (await emulatorRunning()) ? 'booting' : 'off');
  }
  const boot = await quiet(['shell', 'getprop', 'sys.boot_completed']);
  if (boot === null) {
    silentChecks += 1;
    if (silentChecks >= 3 && emu !== 'hung') {
      emu = 'hung';
      log('에뮬레이터가 멈췄어요. [에뮬레이터 다시 켜기]를 눌러 주세요');
    }
    return emu;
  }
  silentChecks = 0;
  return (emu = boot === '1' ? 'ready' : 'booting');
}

let appUser = null;
const user = async () => (appUser ??= (await quiet(['shell', 'am', 'get-current-user'])) || '0');
const asApp = async (...cmd) => (await quiet(['shell', 'run-as', PKG, '--user', await user(), ...cmd])) ?? '';

async function storyServerUp() {
  try {
    return (await fetch('http://127.0.0.1:8787/health', { signal: AbortSignal.timeout(800) })).ok;
  } catch {
    return false;
  }
}

function detached(cmd, args, opts = {}) {
  const out = opts.logFile ? openSync(opts.logFile, 'a') : 'ignore';
  const child = spawn(cmd, args, { cwd: opts.cwd, detached: true, stdio: ['ignore', out, out], windowsHide: true });
  // a missing program (no server/.venv, no emulator) must not take the remote down with it
  child.on('error', (e) => log(`실행 실패: ${cmd} (${e.code || e.message})`));
  child.unref();
}

// After a crash the emulator keeps the report and, on the next start, waits on a "send report?" window
// before booting. Moving finished reports aside lets it boot straight away.
function setCrashReportsAside() {
  const dir = join(tmpdir(), 'AndroidEmulator');
  try {
    for (const name of readdirSync(dir)) {
      if (!/^emu-crash-.*\.db$/.test(name)) continue;
      const reports = join(dir, name, 'reports');
      if (existsSync(reports) && readdirSync(reports).some((f) => f.endsWith('.dmp'))) {
        renameSync(join(dir, name), join(dir, `${name}.old-${Date.now()}`));
        log('지난번 에뮬레이터 오류 보고를 옆으로 치웠어요');
      }
    }
  } catch {}
}

// With the host video decoder on, the emulator calls the NVIDIA driver's cuInit soon after the app starts and freezes there.
const EMULATOR_ARGS = ['-avd', 'pleos', '-no-snapshot-save', '-gpu', 'swiftshader_indirect', '-feature', '-HardwareDecoder'];

function launchEmulator() {
  setCrashReportsAside();
  detached(EMULATOR, EMULATOR_ARGS);
  emu = 'booting';
  silentChecks = 0;
  appUser = null;
  log('에뮬레이터를 켜는 중이에요(1분쯤 걸려요)');
}

/** Every button on the page; each one is the same step a command in 에뮬레이터_테스트_방법.md would run. */
const ACTIONS = {
  // 준비
  emulator: async () => {
    const now = await emulatorState();
    if (now === 'hung') return log('에뮬레이터가 멈춰 있어요. [에뮬레이터 다시 켜기]를 눌러 주세요');
    if (now !== 'off') return log('에뮬레이터가 이미 켜져 있어요');
    launchEmulator();
  },
  emulatorRestart: async () => {
    dropCar();
    log('에뮬레이터를 끄는 중이에요');
    if (WIN) for (const p of EMULATOR_PROCS) await run('taskkill', ['/F', '/T', '/IM', p]);
    else await run('pkill', ['-9', '-f', '--', AVD_MATCH]);
    for (let i = 0; i < 20 && (await emulatorRunning()); i++) await sleep(500);
    launchEmulator();
  },
  storyServer: async () => {
    if (await storyServerUp()) return log('이야기 서버가 이미 켜져 있어요');
    if (!existsSync(PYTHON)) return log('이야기 서버가 설치돼 있지 않아요(server/.venv). 앱은 기본 이야기로 진행돼요');
    detached(PYTHON, ['-m', 'uvicorn', 'app.main:app', '--env-file', '.env', '--host', '127.0.0.1', '--port', '8787'], {
      cwd: join(ROOT, 'server'),
      logFile: STORY_LOG,
    });
    log('이야기 서버를 켜는 중이에요');
  },
  install: async () => {
    if (!existsSync(APK)) throw new Error('앱 파일이 없어요. 먼저 앱을 빌드해 주세요');
    if (emu !== 'ready') throw new Error('에뮬레이터가 다 켜진 뒤에 눌러 주세요');
    log('앱 설치 중...');
    await adbAsync(['install', '-r', APK], 180_000);
    await adbAsync(['reverse', 'tcp:8787', 'tcp:8787']);
    await adbAsync(['forward', 'tcp:8088', 'tcp:8088']);
    log('앱 설치와 연결 완료');
  },
  appRestart: async () => {
    const u = await user();
    await quiet(['shell', 'am', 'force-stop', '--user', u, PKG]);
    await quiet(['shell', 'am', 'start', '--user', u, '-n', `${PKG}/.MainActivity`]);
    log('앱을 다시 켰어요');
  },
  // 시작 조건
  ready: async () => {
    const c = await car();
    await c.set('gear-selection', 'Park');
    await c.set('seat-occupancy', true, 'driver');
    await c.set('hvac-power', true);
  },
  seat: async ({ seat, on }) => (await car()).set('seat-occupancy', on, seatName(seat)),
  power: async ({ on }) => (await car()).set('hvac-power', on),
  // 퍼즐
  dir: async ({ to }) => {
    const c = await car();
    const v = { face: 1, floor: 2 }[to];
    await c.set('hvac-fan-direction', v, 'driver');
    await c.set('hvac-fan-direction', v, 'passenger');
  },
  fan: async ({ speed }) => (await car()).set('hvac-fan-speed', Number(speed)),
  recirc: async ({ on }) => (await car()).set('hvac-recirc', on),
  rooms: async ({ cold }) => (await car()).rooms(cold),
  defrost: async ({ on }) => (await car()).set('hvac-defroster', on, 'front_defroster_on'),
  light: async ({ seat, on }) => (await car()).light(seat, on),
  together: async ({ rhythm, child }) => (await car()).together(rhythm, child),
  // 처음부터
  resetFriends: async () => {
    await asApp('rm', '-f', 'files/friends.txt');
    log('친구 도감을 비웠어요. 앱을 다시 켜면 보여요');
  },
  dropStory: async () => {
    await asApp('rm', '-f', 'files/next_episode.json');
    log('미리 받은 AI 이야기를 버렸어요. 앱을 다시 켜면 새로 만들어요(약 7원)');
  },
};
// these talk to the car or the app, so they wait until the emulator has finished booting
const NEEDS_EMULATOR = new Set(Object.keys(ACTIONS).filter((a) => !['emulator', 'emulatorRestart', 'storyServer', 'install'].includes(a)));

// An emulator restart drops both adb port links, and a PC restart leaves the story server off.
// The remote puts them back by itself, so turning things off and on never needs a button press.
let lastLinkCheck = 0;
let storyStartedAt = 0;
async function keepLinked(ready) {
  if (Date.now() - lastLinkCheck < 5000) return;
  lastLinkCheck = Date.now();
  // the server takes a few seconds to come up; starting it again meanwhile would only fail on the port
  // without server/.venv there is nothing to start; the app plays its own lines
  if (existsSync(PYTHON) && Date.now() - storyStartedAt > 30_000 && !(await storyServerUp())) {
    storyStartedAt = Date.now();
    await ACTIONS.storyServer();
  }
  if (!ready) return;
  const reverse = await quiet(['reverse', '--list']);
  if (reverse !== null && !reverse.includes('tcp:8787')) {
    await quiet(['reverse', 'tcp:8787', 'tcp:8787']);
    log('앱과 이야기 서버를 다시 연결했어요');
  }
  const forward = await quiet(['forward', '--list']);
  if (forward !== null && !forward.includes('tcp:8088')) {
    await quiet(['forward', 'tcp:8088', 'tcp:8088']);
    log('리모컨과 에뮬레이터 콘솔을 다시 연결했어요');
  }
}

async function readStatus() {
  const state = await emulatorState();
  const ready = state === 'ready';
  await keepLinked(ready);
  const s = { emulator: state, storyServer: await storyServerUp(), app: false, friends: [], car: null, logs };
  if (!ready) {
    dropCar();
    if (state !== 'hung') appUser = null;
    return s;
  }
  const [pid, friends] = await Promise.all([quiet(['shell', 'pidof', PKG]), asApp('cat', 'files/friends.txt')]);
  s.app = !!pid;
  s.friends = friends.split(/\s+/).filter((x) => /^[A-Z_]+$/.test(x));
  try {
    const st = await (await car()).state();
    const h = st.hvac;
    s.car = {
      park: st.gear?.actual?.value === 'Park',
      driverSeated: !!st.seat?.occupied?.driver,
      power: !!h.power_on,
      recirc: !!h.recirc_on,
      dual: !!h.dual_on,
      fan: h.fan_speed,
      dir: h.driver?.fan_direction_label,
      tempLeft: h.driver?.temp_c,
      tempRight: h.passenger?.temp_c,
      defrost: !!h.front_defroster_on,
      lights: st.body?.reading_lights || {},
    };
  } catch {
    dropCar();
  }
  return s;
}

// the page asks every 1.5 s; while one check is still running, later requests share its answer instead of piling up
let pending = null;
const status = () => (pending ??= readStatus().finally(() => (pending = null)));

const send = (res, code, body, type = 'application/json; charset=utf-8') => {
  res.writeHead(code, { 'content-type': type, 'cache-control': 'no-store' });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
};

createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/') return send(res, 200, readFileSync(join(HERE, 'index.html'), 'utf8'), 'text/html; charset=utf-8');
    if (req.method === 'GET' && req.url === '/api/status') return send(res, 200, await status());
    if (req.method === 'POST' && req.url === '/api/run') {
      let raw = '';
      for await (const chunk of req) raw += chunk;
      const { action, ...args } = JSON.parse(raw || '{}');
      const act = ACTIONS[action];
      if (!act) return send(res, 404, { error: `모르는 버튼: ${action}` });
      if (NEEDS_EMULATOR.has(action) && emu !== 'ready') {
        throw new Error(emu === 'hung' ? '에뮬레이터가 멈춰 있어요. [에뮬레이터 다시 켜기]를 눌러 주세요' : '에뮬레이터가 다 켜진 뒤에 눌러 주세요');
      }
      await act(args);
      // the console change reaches the car only after a session signal or two
      await sleep(300);
      return send(res, 200, { ok: true });
    }
    send(res, 404, { error: 'not found' });
  } catch (e) {
    log(`실패: ${e.message}`);
    send(res, 500, { error: e.message });
  }
}).listen(PORT, '127.0.0.1', () => console.log(`퍼즐 리모컨: http://127.0.0.1:${PORT}`));
