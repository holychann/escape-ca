// Talks to the Pleos emulator's Virtual Console the way its own page does. Used by tools/vc.mjs (one command)
// and tools/remote/server.mjs (the puzzle remote window).
// The emulator only passes a console change on to the car (and so to the app) while a console session is alive,
// so a connection keeps sending the session signal until it is closed.
import { execFile, execFileSync } from 'node:child_process';
import { join } from 'node:path';

export const ADB = process.env.ADB || join(process.env.LOCALAPPDATA || '', 'Android/Sdk/platform-tools/adb.exe');
export const VC = 'http://127.0.0.1:8088';
/** Reading lights name their areas by position; the seat sensor uses driver/passenger/rear_*. */
export const LIGHT_AREA = { driver: 'front_left', passenger: 'front_right', rear_left: 'rear_left', rear_right: 'rear_right' };
export const RHYTHMS = ['same_time', 'child_once_parent_twice', 'fan_while_lit'];
export const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

export function seatName(s) {
  if (!LIGHT_AREA[s]) throw new Error(`자리 이름: ${Object.keys(LIGHT_AREA).join(', ')}`);
  return s;
}

export function adb(...args) {
  return execFileSync(ADB, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 60_000 });
}

/** Same as adb() without blocking, so a frozen emulator cannot freeze the puzzle remote along with it. */
export function adbAsync(args, timeout = 8000) {
  return new Promise((ok, fail) => {
    execFile(ADB, args, { encoding: 'utf8', timeout, windowsHide: true }, (err, out) => (err ? fail(err) : ok(out)));
  });
}

/** Console requests give up after a few seconds instead of waiting on a frozen emulator forever. */
const call = (path, method = 'GET') => fetch(`${VC}${path}`, { method, signal: AbortSignal.timeout(5000) });

export async function connect({ log = console.log } = {}) {
  await adbAsync(['forward', 'tcp:8088', 'tcp:8088']);
  const session = () => call('/browser/session?client=escape-vc', 'POST').catch(() => {});
  await session();
  const keep = setInterval(session, 1000);
  let catalog;
  try {
    catalog = await (await call('/vehicle/catalog')).json();
  } catch (e) {
    clearInterval(keep);
    throw new Error(`에뮬레이터 콘솔이 대답하지 않아요 (${e.message})`);
  }

  /** The same request the console page sends for one feature. */
  async function set(id, value, area) {
    const f = catalog.features.find((x) => x.id === id);
    if (!f) throw new Error(`콘솔에 없는 항목: ${id}`);
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(f.control.params || {})) p.set(k, String(v));
    p.set((f.control.field_by_area || {})[String(area)] || f.control.field, String(value));
    if (f.control.area_field && area !== undefined) p.set(f.control.area_field, String(area));
    for (let i = 0; i < 6; i++) {
      const text = await (await call(`${f.write_endpoint}?${p}`, 'PUT')).text();
      if (!text.includes('emulator_inactive')) {
        log(`${id}${area ? ` ${area}` : ''} = ${value}`);
        return;
      }
      await sleep(500);
    }
    throw new Error('에뮬레이터가 응답하지 않아요');
  }

  const light = (seat, on) => set('reading-lights-switch', on, LIGHT_AREA[seatName(seat)]);

  /** ⑥ 하나 둘 셋, 동시에!: both lights start off so every step is a fresh "turned on". */
  async function together(rhythm, child) {
    if (!RHYTHMS.includes(rhythm)) throw new Error(`박자: ${RHYTHMS.join(', ')}`);
    await light(child, false);
    await light('driver', false);
    await sleep(1500);
    if (rhythm === 'same_time') {
      await light(child, true);
      await light('driver', true);
    } else if (rhythm === 'child_once_parent_twice') {
      await light(child, true);
      await sleep(700);
      await light('driver', true);
      await sleep(700);
      await light('driver', false);
      await sleep(700);
      await light('driver', true);
    } else {
      await set('hvac-fan-speed', 2);
      await sleep(1000);
      await light(child, true);
      await sleep(700);
      await set('hvac-fan-speed', 6);
    }
  }

  /** ③ 두 방 온도: the driver side first, then the passenger side, which also uncouples the zones (HVAC_DUAL_ON off). */
  async function rooms(cold) {
    if (cold !== 'left' && cold !== 'right') throw new Error('시원한 쪽: left 또는 right');
    await set('hvac-temperature-set', cold === 'left' ? 18 : 24, 'driver');
    await sleep(500);
    await set('hvac-temperature-set', cold === 'left' ? 24 : 18, 'passenger');
  }

  const state = async () => (await call('/vehicle/state')).json();
  const close = () => clearInterval(keep);
  return { catalog, set, light, together, rooms, state, close };
}
