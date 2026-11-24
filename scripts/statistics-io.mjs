import fs from 'node:fs';
import zlib from 'node:zlib';
import readline from 'node:readline';
import crypto from 'node:crypto';

export function csv(line) {
  return [...line.matchAll(/(?:^|,)("(?:[^"]|"")*"|[^,]*)/g)]
    .map(m => m[1].startsWith('"') ? m[1].slice(1, -1).replaceAll('""', '"') : m[1]);
}
export async function gzipRows(file, visit) {
  const input = fs.createReadStream(file).pipe(zlib.createGunzip());
  const lines = readline.createInterface({ input });
  let header;
  for await (let line of lines) {
    line = line.replace(/^\uFEFF/, '');
    if (!header) { header = csv(line); continue; }
    const values = csv(line);
    visit(Object.fromEntries(header.map((h, i) => [h, values[i]])));
  }
}
export function zipEntries(file) {
  const b = fs.readFileSync(file);
  let end = b.length - 22;
  while (end >= 0 && b.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0) throw Error('Invalid ZIP');
  let p = b.readUInt32LE(end + 16);
  const entries = {};
  while (b.readUInt32LE(p) === 0x02014b50) {
    const length = b.readUInt32LE(p + 20), nl = b.readUInt16LE(p + 28), el = b.readUInt16LE(p + 30), cl = b.readUInt16LE(p + 32), offset = b.readUInt32LE(p + 42);
    const name = b.toString('utf8', p + 46, p + 46 + nl);
    const start = offset + 30 + b.readUInt16LE(offset + 26) + b.readUInt16LE(offset + 28);
    const data = b.subarray(start, start + length);
    entries[name] = b.readUInt16LE(p + 10) === 8 ? zlib.inflateRawSync(data) : data;
    p += 46 + nl + el + cl;
  }
  return entries;
}
export function wdi(file, year) {
  const entries = zipEntries(file);
  const entry = Object.entries(entries).find(([name]) => name.startsWith('API_'));
  const values = new Map();
  let header, updated;
  for (const line of entry[1].toString('utf8').split(/\r?\n/)) {
    const row = csv(line.replace(/^\uFEFF/, ''));
    if (row[0] === 'Last Updated Date') updated = row[1];
    if (row[0] === 'Country Name') { header = row; continue; }
    if (header && row[1]) {
      const value = row[header.indexOf(String(year))];
      if (value !== undefined && value !== '') values.set(row[1], Number(value));
    }
  }
  return { values, updated };
}
export const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
export async function fileHash(file) {
  const hash = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}
