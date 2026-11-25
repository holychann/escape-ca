// Changes car values on the Pleos emulator through its Virtual Console, one command at a time.
//
// node tools/vc.mjs light <seat> on|off        seat: driver, passenger, rear_left, rear_right
// node tools/vc.mjs seat <seat> on|off         someone sitting there (the game needs the driver seat taken)
// node tools/vc.mjs together same_time <child seat>
// node tools/vc.mjs together child_once_parent_twice <child seat>
// node tools/vc.mjs together fan_while_lit <child seat>
// node tools/vc.mjs rooms left|right            ③ 두 방 온도: the named side 18 degrees, the other 24
// node tools/vc.mjs fan <0~8>
// node tools/vc.mjs dir <driver|passenger> face|floor
// node tools/vc.mjs recirc on|off
// node tools/vc.mjs dual on|off
// node tools/vc.mjs temp <driver|passenger> <16~30>
// node tools/vc.mjs defrost on|off
// node tools/vc.mjs power on|off
// node tools/vc.mjs keep [seconds]              holds the console session open (default 60 s)
// node tools/vc.mjs show                        lists what the console can change
import { connect, seatName, sleep } from './vc-lib.mjs';

const onOff = (v) => {
  if (v !== 'on' && v !== 'off') throw new Error(`on 또는 off: ${v}`);
  return v === 'on';
};

const [cmd, a, b] = process.argv.slice(2);
let vc;
try {
  vc = await connect();
  if (cmd === 'light') await vc.light(a, onOff(b));
  else if (cmd === 'seat') await vc.set('seat-occupancy', onOff(b), seatName(a));
  else if (cmd === 'together') await vc.together(a, b);
  else if (cmd === 'rooms') await vc.rooms(a);
  else if (cmd === 'fan') await vc.set('hvac-fan-speed', Number(a));
  else if (cmd === 'dir') await vc.set('hvac-fan-direction', { face: 1, floor: 2 }[b] ?? b, a);
  else if (cmd === 'recirc') await vc.set('hvac-recirc', onOff(a));
  else if (cmd === 'dual') await vc.set('hvac-dual', onOff(a));
  else if (cmd === 'temp') await vc.set('hvac-temperature-set', Number(b), a);
  else if (cmd === 'defrost') await vc.set('hvac-defroster', onOff(a), 'front_defroster_on');
  else if (cmd === 'power') await vc.set('hvac-power', onOff(a));
  else if (cmd === 'keep') await sleep(Number(a || 60) * 1000);
  else if (cmd === 'show') {
    for (const f of vc.catalog.features) {
      if (/reading|hvac/.test(f.id)) console.log(f.id, JSON.stringify(f.control));
    }
  } else {
    console.log('사용법은 tools/vc.mjs 맨 위 주석을 보세요');
  }
  // the car picks the change up only while the session is alive
  await sleep(2000);
} catch (e) {
  console.error(e.message);
  process.exitCode = 1;
} finally {
  vc?.close();
}
