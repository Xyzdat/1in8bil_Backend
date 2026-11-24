// Offline extraction of downloaded official releases. No network access in game/tests.
import fs from 'node:fs';
import zlib from 'node:zlib';
import readline from 'node:readline';
import crypto from 'node:crypto';
const dir = 'data/statistics/expanded';
const out = 'src/main/resources/statistics/expanded';
fs.mkdirSync(out, {recursive:true});
function csv(line) { return [...line.matchAll(/(?:^|,)("(?:[^"]|"")*"|[^,]*)/g)].map(m=>m[1].startsWith('"')?m[1].slice(1,-1).replaceAll('""','"'):m[1]); }
async function rows(file, visit) {
  const input = fs.createReadStream(`${dir}/${file}`).pipe(zlib.createGunzip());
  const lines = readline.createInterface({input}); let header;
  for await (let line of lines) { line=line.replace(/^\uFEFF/,''); if(!header){header=csv(line);continue;} const values=csv(line); visit(Object.fromEntries(header.map((h,i)=>[h,values[i]]))); }
}
function zipEntries(file) {
  const b=fs.readFileSync(file); let end=b.length-22;
  while(end>=0 && b.readUInt32LE(end)!==0x06054b50) end--;
  if(end<0)throw Error('Invalid ZIP');
  let p=b.readUInt32LE(end+16);const entries={};
  while(b.readUInt32LE(p)===0x02014b50){
    const len=b.readUInt32LE(p+20),nl=b.readUInt16LE(p+28),el=b.readUInt16LE(p+30),cl=b.readUInt16LE(p+32),offset=b.readUInt32LE(p+42);
    const name=b.toString('utf8',p+46,p+46+nl);const start=offset+30+b.readUInt16LE(offset+26)+b.readUInt16LE(offset+28);
    const data=b.subarray(start,start+len);entries[name]=b.readUInt16LE(p+10)===8?zlib.inflateRawSync(data):data;p+=46+nl+el+cl;
  }return entries;
}
const wanted=new Set(['RUS','JPN','IND']);const demographics={};let worldBirths;
await rows('demographic.csv.gz',r=>{if(r.Time==='2026'&&r.Variant==='Medium') { if(r.Location==='World')worldBirths=Number(r.Births)*1000; if(wanted.has(r.ISO3_code))demographics[r.ISO3_code]=r; }});
if(!worldBirths || Object.keys(demographics).length!==3)throw Error('Demographic coverage');
const mortality=[];
for(const sex of ['male','female']) await rows(`${sex}.csv.gz`,r=>{
  if(wanted.has(r.ISO3_code)&&r.Variant==='Medium') mortality.push([r.ISO3_code,sex==='male'?'MALE':'FEMALE',+r.Time,+r.AgeGrpStart,+r.qx,+r.mx]);
});
if(mortality.length!==3*2*77*101)throw Error(`Mortality coverage ${mortality.length}`);
if(mortality.some(r=>r[4]<0||r[4]>1||!Number.isFinite(r[4])||r[5]<0))throw Error('Mortality bounds');
const entries=zipEntries(`${dir}/gdp.zip`);
const gdp={};let header;
for(const line of Object.entries(entries).find(([n])=>n.startsWith('API_'))[1].toString('utf8').split(/\r?\n/)){
  const c=csv(line);if(c[0]==='Country Name'){header=c;continue;} if(header&&wanted.has(c[1]))gdp[c[1]]=Number(c[header.indexOf('2023')]);
}
const territories=JSON.parse(fs.readFileSync(`${dir}/territoryInfo.json`)).supplemental.territoryInfo;
const currencies=JSON.parse(fs.readFileSync(`${dir}/currencyData.json`)).supplemental.currencyData.region;
const profiles=Object.entries(demographics).map(([iso,r])=>{
 const iso2=r.ISO2_code;
 const languages=Object.entries(territories[iso2].languagePopulation).map(([code,v])=>({code,percent:Number(v._populationPercent),status:v._officialStatus??null}));
 const currency=currencies[iso2].flatMap(item=>Object.entries(item)).find(([code,v])=>!v._to && v._tender!=='false')?.[0];
 if(!currency||!gdp[iso])throw Error('Country data missing');
 const countryName={IND:'India',RUS:'Russia',JPN:'Japan'}[iso];
 const religionRows=[...fs.readFileSync(`${dir}/religions.html`,'utf8').matchAll(/\{"Region":"[^{}]+\}/g)].map(m=>JSON.parse(m[0]));
 const row=religionRows.find(x=>x.Country===countryName&&x.Year==='2020'&&x.Total==='100');
 if(!row)throw Error(`Public religion table missing ${countryName}`);
 const religions=Object.entries(row).filter(([k])=>['Christians','Muslims','Unaffiliated','Buddhists','Hindus','Jews','Other religions'].includes(k)).map(([code,v])=>({code,percent:Number(v)}));
 return {iso3:iso,iso2,name:r.Location,births:Number(r.Births)*1000,worldBirths,lifeExpectancy:Number(r.LEx),underFiveProbability:Number(r.Q5)/1000,gdpPerCapitaUsd:gdp[iso],currency,languages,religions,year:2026,gdpYear:2023};
});
console.log('Demographic columns',Object.keys(demographics.IND));
console.log(profiles);
if(profiles.some(p=>!Number.isFinite(p.lifeExpectancy)||!Number.isFinite(p.underFiveProbability)))throw Error('Indicator mapping');
fs.writeFileSync(`${out}/mortality.json`,JSON.stringify(mortality));
fs.writeFileSync(`${out}/countries.json`,JSON.stringify(profiles,null,2));
const previous=fs.existsSync(`${out}/manifest.json`)?JSON.parse(fs.readFileSync(`${out}/manifest.json`)):null;
const hashes={};for(const f of ['mortality.json','countries.json'])hashes[f]=crypto.createHash('sha256').update(fs.readFileSync(`${out}/${f}`)).digest('hex');
const inputs={};for(const f of ['male.csv.gz','female.csv.gz','demographic.csv.gz','gdp.zip','territoryInfo.json','currencyData.json','religions.html']) inputs[f]=crypto.createHash('sha256').update(fs.readFileSync(`${dir}/${f}`)).digest('hex');
if(previous&&(JSON.stringify(previous.hashes)!==JSON.stringify(hashes)||JSON.stringify(previous.inputs)!==JSON.stringify(inputs)))throw Error('Changed source: publish a new release ID rather than overwrite an imported release.');
fs.writeFileSync(`${out}/manifest.json`,JSON.stringify({id:'birth-context-2026-v1',retrievedAt:previous?.retrievedAt??new Date().toISOString(),hashes,inputs,licenses:['UN WPP 2024: CC BY 3.0 IGO','World Bank WDI: CC BY 4.0','Unicode CLDR 48: Unicode License V3','Pew Research Center public table: Terms of Use, attribution required']},null,2));
console.log(`Extracted ${mortality.length} mortality rows and ${profiles.length} country profiles`);
