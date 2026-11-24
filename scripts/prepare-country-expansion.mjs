// Builds an immutable, offline release; old snapshots are never modified.
import fs from 'node:fs';
import zlib from 'node:zlib';
import { gzipRows, wdi, sha256, fileHash } from './statistics-io.mjs';

const source = 'data/statistics/expanded';
const output = 'src/main/resources/statistics/expanded-v2';
const catalog = JSON.parse(fs.readFileSync('src/main/resources/statistics/country-catalog.json')).sort((a,b) => a.iso3.localeCompare(b.iso3));
const wanted = new Set(catalog.map(c => c.iso3));
if (wanted.size !== catalog.length) throw Error('Duplicate country');
const original = JSON.parse(fs.readFileSync('src/main/resources/statistics/expanded/manifest.json'));
const inputHashes = {};
for (const file of [...Object.keys(original.inputs), 'urban.zip']) {
  inputHashes[file] = await fileHash(`${source}/${file}`);
  if (original.inputs[file] && inputHashes[file] !== original.inputs[file]) throw Error(`Changed original source: ${file}`);
}
const demographics = new Map();
let worldBirths;
await gzipRows(`${source}/demographic.csv.gz`, r => {
  if (r.Time !== '2026' || r.Variant !== 'Medium') return;
  if (r.Location === 'World') worldBirths = Number(r.Births) * 1000;
  if (wanted.has(r.ISO3_code)) demographics.set(r.ISO3_code, r);
});
const gdp = wdi(`${source}/gdp.zip`, 2023);
const urban = wdi(`${source}/urban.zip`, 2023);
const territories = JSON.parse(fs.readFileSync(`${source}/territoryInfo.json`)).supplemental.territoryInfo;
const currencies = JSON.parse(fs.readFileSync(`${source}/currencyData.json`)).supplemental.currencyData.region;
const religionsTable = [...fs.readFileSync(`${source}/religions.html`, 'utf8').matchAll(/\{"Region":"[^{}]+\}/g)].map(m => JSON.parse(m[0]));
const religionCodes = new Set(['Christians','Muslims','Unaffiliated','Buddhists','Hindus','Jews','Other religions']);
const profiles = catalog.map(c => {
  const r = demographics.get(c.iso3);
  const religionRow = religionsTable.find(r => r.Country === c.pew && r.Year === '2020' && r.Total === '100');
  if (!r || r.ISO2_code !== c.iso2 || !religionRow || !gdp.values.has(c.iso3) || !urban.values.has(c.iso3)) throw Error(`Source coverage missing: ${c.iso3}`);
  const languages = Object.entries(territories[c.iso2].languagePopulation).map(([code,v]) => ({code,percent:Number(v._populationPercent),status:v._officialStatus??null}));
  const legalCurrencies = currencies[c.iso2].flatMap(item => Object.entries(item)).filter(([,v]) => !v._to && v._tender !== 'false');
  if (legalCurrencies.length !== 1) throw Error(`Ambiguous currency: ${c.iso3}`);
  const religions = Object.entries(religionRow).filter(([k]) => religionCodes.has(k)).map(([code,v]) => ({code,percent:Number(v)}));
  const p = {iso3:c.iso3,iso2:c.iso2,name:c.name,births:Number(r.Births)*1000,worldBirths,
    lifeExpectancy:Number(r.LEx),underFiveProbability:Number(r.Q5)/1000,
    gdpPerCapitaUsd:gdp.values.get(c.iso3),currency:legalCurrencies[0][0],languages,religions,year:2026,gdpYear:2023,
    maleFemaleRatio:Number(r.SRB)/100,urbanShare:urban.values.get(c.iso3)/100,sexRatioYear:2026,urbanYear:2023};
  if (![p.births,p.worldBirths,p.lifeExpectancy,p.underFiveProbability,p.gdpPerCapitaUsd,p.maleFemaleRatio,p.urbanShare].every(Number.isFinite)
    || p.births<=0 || p.worldBirths<=p.births || p.lifeExpectancy<20 || p.lifeExpectancy>120 || p.underFiveProbability<0 || p.underFiveProbability>1
    || p.gdpPerCapitaUsd<=0 || p.maleFemaleRatio<0.8 || p.maleFemaleRatio>1.3 || p.urbanShare<0 || p.urbanShare>1
    || religions.length!==7 || Math.abs(religions.reduce((sum,x)=>sum+x.percent,0)-100)>0.01
    || [...religions,...languages].some(x=>!Number.isFinite(x.percent)||x.percent<0||x.percent>100)) throw Error(`Invalid profile: ${c.iso3}`);
  return p;
});
console.log(`Verified country profiles: ${profiles.length}; WDI updated GDP=${gdp.updated}, urban=${urban.updated}`);
const mortality = new Map(catalog.map(c => [c.iso3, []]));
for (const sex of ['male','female']) {
  await gzipRows(`${source}/${sex}.csv.gz`, r => {
    if (wanted.has(r.ISO3_code) && r.Variant === 'Medium') mortality.get(r.ISO3_code).push([r.ISO3_code, sex.toUpperCase(), +r.Time, +r.AgeGrpStart, +r.qx, +r.mx]);
  });
  console.log(`Extracted ${sex} mortality`);
}
const files = new Map([['countries.json', Buffer.from(JSON.stringify(profiles,null,2)+'\n')]]);
for (const [iso,rows] of mortality) {
  const seen = new Set();
  for (const r of rows) {
    const key = `${r[1]}:${r[2]}:${r[3]}`;
    if (r[2]<2024 || r[2]>2100 || r[3]<0 || r[3]>100 || r[4]<0 || r[4]>1 || r[5]<0 || !r.slice(2).every(Number.isFinite) || seen.has(key)) throw Error(`Invalid mortality: ${iso}`);
    seen.add(key);
  }
  if (seen.size !== 15554) throw Error(`Incomplete mortality: ${iso} ${seen.size}`);
  // Verify that all previously supported countries keep exactly the same mortality observations.
  if (['IND','JPN','RUS'].includes(iso)) {
    const old = JSON.parse(fs.readFileSync('src/main/resources/statistics/expanded/mortality.json')).filter(r=>r[0]===iso);
    if (JSON.stringify(rows)!==JSON.stringify(old)) throw Error(`Legacy mortality changed: ${iso}`);
  }
  files.set(`mortality/${iso}.json.gz`, zlib.gzipSync(JSON.stringify(rows), {level:9}));
}
const hashes = Object.fromEntries([...files].map(([name,bytes]) => [name,sha256(bytes)]));
const manifestPath = `${output}/manifest.json`;
const previous = fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath)) : null;
if (previous && (JSON.stringify(previous.hashes)!==JSON.stringify(hashes) || JSON.stringify(previous.inputs)!==JSON.stringify(inputHashes))) throw Error('Immutable release changed: choose a new release ID.');
const manifest = {id:'birth-context-2026-v2',retrievedAt:previous?.retrievedAt??new Date().toISOString(),countries:catalog.map(c=>c.iso3),countryCount:catalog.length,
  mortalityRows:catalog.length*15554,hashes,inputs:inputHashes,wdiUpdated:{gdp:gdp.updated,urban:urban.updated},licenses:original.licenses,
  sources:{demography:'https://population.un.org/wpp/',gdp:'https://api.worldbank.org/v2/en/indicator/NY.GDP.PCAP.CD?downloadformat=csv',
    urban:'https://api.worldbank.org/v2/en/indicator/SP.URB.TOTL.IN.ZS?downloadformat=csv',cldr:'https://github.com/unicode-org/cldr-json/tree/48.0.0',religions:'https://www.pewresearch.org/feature/religious-composition-by-country-2010-2070/'}};
fs.mkdirSync(`${output}/mortality`,{recursive:true});
for (const [name,bytes] of files) fs.writeFileSync(`${output}/${name}`,bytes);
fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2)+'\n');
fs.copyFileSync(`${source}/UNICODE-LICENSE.txt`,`${output}/UNICODE-LICENSE.txt`);
console.log(`Release ${manifest.id}: ${profiles.length} countries, ${manifest.mortalityRows} mortality rows`);
