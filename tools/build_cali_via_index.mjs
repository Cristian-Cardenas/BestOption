import fs from 'node:fs';

// Agrega al índice las vías con sus barrios (V|...) y algunos cruces que no
// existen en la capa oficial pero sí aparecen en las ofertas de la app.
// Uso:
//   node tools/build_cali_via_index.mjs [asset] [cali_addresses.json]
const assetPath = process.argv[2] || 'android/app/src/main/assets/cali_barrios_index.txt';
const addressesPath = process.argv[3] || 'C:/Users/crisc/AppData/Local/Temp/cali_addresses.json';

// Calle 49 Norte #8 Norte-75 existe en La Flora, pero la capa de direcciones
// solo tiene esa vía con cruces de avenidas (AV 3C, AV 4A, AV 5, AV 6).
const MANUAL_PAIRS = [
  ['CALLE 49 NORTE', '8', 'La Flora', '02'],
];

const normName = (s) => (s || '')
  .normalize('NFD')
  .replace(/[\u0300-\u036f]/g, '')
  .toUpperCase()
  .replace(/[^\p{L}\p{N}]+/gu, ' ')
  .replace(/\s+/g, ' ')
  .trim();

const original = fs.readFileSync(assetPath, 'utf8').replace(/\r\n/g, '\n');
const baseLines = original.split('\n').filter((l) => l.trim() && !l.startsWith('V|'));

const byName = new Map();
for (const line of baseLines) {
  if (!line.startsWith('B|')) continue;
  const [, barrio, comuna] = line.split('|');
  byName.set(normName(barrio), { barrio, comuna });
}

const rows = JSON.parse(fs.readFileSync(addressesPath, 'utf8'));
const counts = new Map();
for (const r of rows) {
  if (!r.via || !r.barrio || !r.comuna) continue;
  const b = byName.get(normName(r.barrio));
  if (!b) continue;
  const key = `${r.via}\u0001${b.barrio}\u0001${b.comuna}`;
  counts.set(key, (counts.get(key) || 0) + 1);
}

const vLines = [...counts.entries()]
  .map(([key, count]) => {
    const [via, barrio, comuna] = key.split('\u0001');
    return { via, barrio, comuna, count };
  })
  .sort((a, b) =>
    a.via.localeCompare(b.via) ||
    b.count - a.count ||
    a.barrio.localeCompare(b.barrio)
  )
  .map((e) => `V|${e.via}|${e.barrio}|${e.comuna}|${e.count}`);

const existingPairs = new Set(baseLines.filter((l) => l.startsWith('P|')));
const manualPairs = MANUAL_PAIRS
  .map(([via, cross, barrio, comuna]) => `P|${via}|${cross}|${barrio}|${comuna}`)
  .filter((line) => !existingPairs.has(line));

const out = [...baseLines, ...manualPairs, ...vLines].join('\n') + '\n';
fs.writeFileSync(assetPath, out, 'utf8');
console.log(JSON.stringify({
  assetPath,
  pairsAdded: manualPairs.length,
  viaBarrioRows: vLines.length,
  totalLines: baseLines.length + manualPairs.length + vLines.length,
  bytes: Buffer.byteLength(out),
}, null, 2));
