const mc = require(process.env.CMB_WALL_PROTOCOL);
const client = mc.createClient({
  host: '127.0.0.1', port: Number(process.env.CMB_WALL_PORT),
  username: 'ParityProbe', auth: 'offline', version: '26.3',
});

client.on('position', p => {
  client.write('teleport_confirm', { teleportId: p.teleportId, x: p.x, y: p.y, z: p.z, yRot: p.yaw, xRot: p.pitch });
  client.write('position_look', { x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch,
    flags: { onGround: true, hasHorizontalCollision: false } });
  client.write('tick_end', {});
});
client.on('packet', (data, meta) => {
  if (meta.name !== 'system_chat') return;
  const action = JSON.stringify(data).match(/\[WALLTEST-ACTION\] (attack|click|clickup|offhand|dig|side|normal|deny|gateplace|gateuse|snowplace) (\d+)/);
  if (!action) return;
  const kind = action[1];
  const id = Number(action[2]);
  console.log('Action', kind, id);
  if (kind === 'attack') {
    client.write('attack', { entityId: id });
  } else if (kind === 'dig') {
    client.write('block_dig', { status: 0, location: { x: id, y: 65, z: 0 }, face: 3, sequence: 1 });
  } else if (kind === 'gateplace' || kind === 'gateuse' || kind === 'snowplace') {
    client.write('player_input', { inputs: {} });
    client.write('block_place', { hand: 'main_hand', location: { x: id, y: kind === 'gateplace' ? 64 : 65, z: 0 },
      direction: kind === 'gateuse' ? 3 : 1, cursorX: .5,
      cursorY: kind === 'snowplace' ? .125 : kind === 'gateplace' ? 1 : .5,
      cursorZ: kind === 'gateplace' ? .5 : 1, insideBlock: false, worldBorderHit: false, sequence: 2 });
  } else {
    const sneaking = kind !== 'normal';
    client.write('player_input', { inputs: sneaking ? { shift: true } : {} });
    client.write('use_entity', { target: id, hand: 'main_hand', location: { x: 0, y: 0, z: 0 }, sneaking });
    if (kind === 'click' || kind === 'clickup' || kind === 'offhand') {
      // A single vanilla click can also try the off hand after interacting with an entity.
      client.write('use_entity', { target: id, hand: 'off_hand', location: { x: 0, y: 0, z: 0 }, sneaking });
    }
  }
});
const tick = setInterval(() => {
  if (client.state === mc.states.PLAY) client.write('tick_end', {});
}, 50);
client.on('error', error => console.error(error));
client.on('end', () => { clearInterval(tick); process.exit(); });
setTimeout(() => { client.end(); process.exit(2); }, 150000).unref();
