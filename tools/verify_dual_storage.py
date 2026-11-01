"""Executes production migration SQL with SQLite. Does not claim Android Room device validation."""
from pathlib import Path
import sqlite3,json,re
root=Path(__file__).resolve().parents[1]
old=json.loads((root/'app/schemas/dev.usagetime.app.data.UsageDatabase/1.json').read_text(encoding='utf-8'))['database']
new=json.loads((root/'app/schemas/dev.usagetime.app.data.UsageDatabase/2.json').read_text(encoding='utf-8'))['database']
source=(root/'app/src/main/java/dev/usagetime/app/data/DualStorage.kt').read_text(encoding='utf-8')
v3=json.loads((root/'app/schemas/dev.usagetime.app.data.UsageDatabase/3.json').read_text(encoding='utf-8'))['database']
head,_,rest=source.partition('val statementsV3')
v3block,_,rest=rest.partition('val statementsV4')
v4block,_,v5block=rest.partition('val statementsV5')
sql=re.findall(r'^        "(CREATE [^"\n]+)"', head, re.M)
sql_v3=re.findall(r'^        "(CREATE [^"\n]+)"', v3block, re.M)
sql_v4=re.findall(r'^        "([^"\n]+)"', v4block, re.M)
sql_v5=re.findall(r'^        "([^"\n]+)"', v5block, re.M)
db=sqlite3.connect(':memory:')
for table in old['entities']:
 db.execute(table['createSql'].replace('${TABLE_NAME}',table['tableName']))
 for index in table.get('indices',[]):db.execute(index['createSql'].replace('${TABLE_NAME}',table['tableName']))
db.execute("INSERT INTO intervals VALUES ('fixture.app',1000,2000)")
db.execute("INSERT INTO coverage VALUES (1000,2000,1)")
db.execute("INSERT INTO cursor VALUES (0,2000,'fixture.app','A',1000,0,1)")
db.execute("INSERT INTO apps VALUES ('fixture.app','Fixture')")
before={name:db.execute(f'SELECT * FROM {name}').fetchall() for name in ['intervals','coverage','cursor','apps']}
for statement in sql:db.execute(statement)
def check_schema(schema):
 for table in schema['entities']:
  info=db.execute(f'PRAGMA table_info({table["tableName"]})').fetchall()
  got={row[1]:(row[2],bool(row[3])) for row in info}
  expected={f['columnName']:(f['affinity'],f.get('notNull',False)) for f in table['fields']}
  assert got==expected,(table['tableName'],got,expected)
check_schema(new)
for name,values in before.items():assert db.execute(f'SELECT * FROM {name}').fetchall()==values
print('PASS: production migration matches Room v2 schema; all four legacy tables retained')
for statement in sql_v3:db.execute(statement)
check_schema(v3)
print('PASS: incremental v2 to v3 migration adds hour and month summary tables without touching existing rows')
for statement in sql_v4:db.execute(statement)
v4=json.loads((root/'app/schemas/dev.usagetime.app.data.UsageDatabase/4.json').read_text(encoding='utf-8'))['database']
check_schema(v4)
plan=db.execute('EXPLAIN QUERY PLAN SELECT MIN(dayStart) FROM daily_usage WHERE zone=:zone',{'zone':'UTC'}).fetchall()
assert any('index_daily_usage_zone_dayStart' in row[3] for row in plan),plan
print('PASS: v3 to v4 migration replaces zone-only indexes with zone+start composites for cache floor lookups')
for statement in sql_v5:db.execute(statement)
v5=json.loads((root/'app/schemas/dev.usagetime.app.data.UsageDatabase/5.json').read_text(encoding='utf-8'))['database']
check_schema(v5)
for table in v5['entities']:
 for index in table.get('indices',[]):
  columns=[r[2] for r in db.execute(f'PRAGMA index_info("{index["name"]}")')]
  assert columns==index['columnNames'],(index['name'],columns,index['columnNames'])
plan=db.execute("EXPLAIN QUERY PLAN SELECT * FROM usage_events WHERE time>=0 AND time<100 AND source!='LEGACY_IMPORT' ORDER BY time,id").fetchall()
assert any('index_usage_events_time_id' in row[3] for row in plan),plan
assert not any('TEMP B-TREE' in row[3] for row in plan),plan
for name,values in before.items():assert db.execute(f'SELECT * FROM {name}').fetchall()==values
print('PASS: v4 to v5 migration preserves history and serves deterministic event ordering without temporary sort')
row=('evt',3000,'RESUME','fixture.app','A',None,'USAGE_EVENTS','UTC','LOCAL',None)
db.execute('INSERT OR IGNORE INTO usage_events VALUES (?,?,?,?,?,?,?,?,?,?)',row)
db.execute('INSERT OR IGNORE INTO usage_events VALUES (?,?,?,?,?,?,?,?,?,?)',row)
assert db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]==1
print('PASS: event identity deduplication')
db.commit()
try:
 with db:
  db.execute('INSERT INTO diagnostic_logs VALUES (?,?,?,?)',('transient',4000,'TEST','will rollback'))
  db.execute('INSERT INTO sync_checkpoints VALUES (?,?,?,?,?,?,?)',(4000,'{}',4000,1000,'boot','UTC',2))
  raise RuntimeError('simulate interruption before commit')
except RuntimeError:pass
assert db.execute('SELECT COUNT(*) FROM sync_checkpoints').fetchone()[0]==0
assert db.execute("SELECT COUNT(*) FROM diagnostic_logs WHERE id='transient'").fetchone()[0]==0
assert db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]==1
print('PASS: interrupted transaction leaves event/checkpoint/log state unchanged')
for _ in range(2):
 with db:
  db.execute('DELETE FROM usage_intervals WHERE start>=3000 AND algorithm=2')
  db.execute('INSERT OR REPLACE INTO usage_intervals VALUES (?,?,?,?,?,?,?,?,?,?,?)',('slice','PRIMARY','fixture.app',3000,5000,'INFERRED','fixture','TEST','evt',0,2))
  db.execute('INSERT OR REPLACE INTO daily_usage VALUES (?,?,?,?,?,?,?,?,?,?)',('day',0,86400000,'UTC','PRIMARY','fixture.app','INFERRED',2000,0,2))
assert db.execute('SELECT COUNT(*),SUM(`end`-start) FROM usage_intervals').fetchone()==(1,2000)
assert db.execute('SELECT COUNT(*),SUM(millis) FROM daily_usage').fetchone()==(1,2000)
print('PASS: repeated derived-range recomputation does not accumulate duplicates')


# Execute the actual DAO queries for cross-chunk finalization and cross-zone invalidation.
source=(root/'app/src/main/java/dev/usagetime/app/data/DualStorage.kt').read_text(encoding='utf-8')
queries=re.findall(r'@Query\("([^"\n]+)"\)',source)
finalize=next(q for q in queries if q.startswith('UPDATE usage_intervals SET evidence='))
invalidate=next(q for q in queries if q=='DELETE FROM daily_usage WHERE dayEnd>:start')
for identity,ref,metric in [('pending','focus-old','PRIMARY'),('other-ref','focus-old-extra','PRIMARY'),('visible','focus-old','VISIBLE')]:
 db.execute('INSERT INTO usage_intervals VALUES (?,?,?,?,?,?,?,?,?,?,?)',(identity,metric,'fixture.app',100,200,'INFERRED','EXPLICIT_WINDOW_FOCUS_OPEN_TAIL','OWN_WINDOW',ref,1,2))
args=dict(metric='PRIMARY',app='fixture.app',origin='focus-old',at=300,prefix='EXPLICIT%',evidence='CONFIRMED',ending=',focus-close')
# Earliest matching start is tested by the streaming core matcher; this fixture starts at 100.
earliest=100
db.execute(finalize,args)
assert db.execute("SELECT evidence,provisional,refs FROM usage_intervals WHERE id='pending'").fetchone()==('CONFIRMED',0,'focus-old,focus-close')
assert db.execute("SELECT provisional FROM usage_intervals WHERE id='other-ref'").fetchone()[0]==1
assert db.execute("SELECT provisional FROM usage_intervals WHERE id='visible'").fetchone()[0]==1
print('PASS: production DAO finalizes only the matching metric and exact evidence reference')
for zone in ['UTC','Asia/Shanghai']:
 db.execute('INSERT OR REPLACE INTO daily_usage VALUES (?,?,?,?,?,?,?,?,?,?)',(zone,0,86400000,zone,'PRIMARY','fixture.app','INFERRED',100,100,2))
db.execute(invalidate,dict(start=earliest))
assert db.execute('SELECT COUNT(*) FROM daily_usage').fetchone()[0]==0
print('PASS: earlier evidence closure invalidates affected caches in every time zone')

store_source=(root/'app/src/main/java/dev/usagetime/app/data/UsageStore.kt').read_text(encoding='utf-8')
first_query=next(q for q in re.findall(r'@Query\("([^"\n]+)"\)',store_source) if 'UNION ALL SELECT start FROM intervals' in q)
fixture=sqlite3.connect(':memory:')
for table in old['entities']:fixture.execute(table['createSql'].replace('${TABLE_NAME}',table['tableName']))
fixture.execute("INSERT INTO intervals VALUES('fixture',1000,2000)")
assert fixture.execute(first_query).fetchone()[0]==1000
assert fixture.execute('SELECT COUNT(*) FROM coverage').fetchone()[0]==0
fixture.execute('INSERT INTO coverage VALUES(500,900,1)')
assert fixture.execute(first_query).fetchone()[0]==500
print('PASS: production ALL start query includes interval-only legacy imports without fabricating coverage')

maintenance_source=(root/'app/src/main/java/dev/usagetime/app/data/DualStorage.kt').read_text(encoding='utf-8')
maintenance_queries=re.findall(r'@Query\("([^"\n]+)"\)',maintenance_source)
trim=next(q for q in maintenance_queries if q.startswith('DELETE FROM daily_usage WHERE algorithm!=3'))
for identity,day,zone in [('old-a',0,'UTC'),('old-summary',0,'UTC'),('mid-a',86400000,'UTC'),('mid-b',86400000,'UTC'),('mid-summary',86400000,'UTC'),('mid-observed',86400000,'UTC'),('new-a',172800000,'UTC'),('new-summary',172800000,'UTC'),('other-zone',172800000,'Asia/Shanghai')]:
 db.execute('INSERT OR REPLACE INTO daily_usage VALUES (?,?,?,?,?,?,?,?,?,?)',(identity,day,day+86400000,zone,'PRIMARY','fixture','INFERRED',1,0,3))
db.execute(trim,dict(zone='UTC',maxRows=3))
assert db.execute('SELECT dayStart,COUNT(*) FROM daily_usage GROUP BY dayStart ORDER BY dayStart').fetchall()==[(86400000,4),(172800000,2)]
print('PASS: cache pruning removes entire dates and never leaves a falsely complete partial day')
trim_logs=next(q for q in maintenance_queries if q.startswith('DELETE FROM diagnostic_logs WHERE code NOT IN'))
for identity,time,code in [('gap-old',1,'RETENTION_GAP'),('noise1',2,'NOISE'),('noise2',3,'NOISE'),('noise3',4,'NOISE'),('noise4',5,'NOISE')]:db.execute('INSERT OR REPLACE INTO diagnostic_logs VALUES (?,?,?,?)',(identity,time,code,''))
db.execute(trim_logs,dict(critical='RETENTION_GAP',keep=2))
assert db.execute("SELECT COUNT(*) FROM diagnostic_logs WHERE id='gap-old'").fetchone()[0]==1
assert db.execute("SELECT COUNT(*) FROM diagnostic_logs WHERE id='noise1'").fetchone()[0]==0
print('PASS: diagnostic retention preserves critical gap evidence')
compact=next(q for q in maintenance_queries if q.startswith('DELETE FROM sync_checkpoints WHERE algorithm=2 AND time<'))
for time in [1000,2000,172801000]:db.execute('INSERT OR REPLACE INTO sync_checkpoints VALUES (?,?,?,?,?,?,?)',(time,'{}',time,1,'boot','UTC',2))
db.execute(compact,dict(before=86400000))
assert db.execute('SELECT time FROM sync_checkpoints ORDER BY time').fetchall()==[(2000,),(172801000,)]
assert db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]==1
assert db.execute("SELECT COUNT(*) FROM intervals WHERE app='fixture.app'").fetchone()[0]==1
print('PASS: checkpoint compaction keeps daily/latest state without deleting usage evidence')

exists=next(q for q in re.findall(r'@Query\("([^"\n]+)"\)',store_source) if q.startswith('SELECT EXISTS'))
assert fixture.execute(exists,dict(start=2000,end=3000)).fetchone()[0]==0
assert fixture.execute(exists,dict(start=1999,end=2000)).fetchone()[0]==1
print('PASS: legacy existence query respects half-open boundaries without loading records')
metric_query=next(q for q in maintenance_queries if q.startswith('SELECT * FROM usage_intervals WHERE metric='))
assert all(row[1]=='VISIBLE' for row in db.execute(metric_query,dict(start=0,end=6000,metric='VISIBLE')))
assert len(db.execute(metric_query,dict(start=0,end=6000,metric='VISIBLE')).fetchall())==1
print('PASS: production history query isolates metrics before interval decoding')

db.execute('INSERT INTO daily_usage VALUES (?,?,?,?,?,?,?,?,?,?)',('stale-cache',0,86400000,'UTC','PRIMARY','old','INFERRED',100,0,2))
db.execute(trim,dict(zone='UTC',maxRows=500000))
assert db.execute("SELECT COUNT(*) FROM daily_usage WHERE id='stale-cache'").fetchone()[0]==0
assert db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]==1
print('PASS: changed reporting algorithm invalidates old caches without removing raw history')

scan=next(q for q in re.findall(r'query\("([^"\n]+)"\)',store_source) if q.startswith('SELECT metric,app,start,refs,reason'))
rows=db.execute(scan).fetchall()
assert {row[3] for row in rows}=={'focus-old-extra','focus-old'}
assert all(row[2]==100 for row in rows)
print('PASS: production closure scan streams only pending local rows without per-session MIN queries')

# Metadata is a reserved algorithm=0 row, not an engine checkpoint.
metadata='{"format":"usage-replay-progress","version":1,"ready":true,"dirtyFrom":10}'
db.execute('INSERT OR REPLACE INTO sync_checkpoints VALUES (?,?,?,?,?,?,?)',(-1,metadata,0,-1,'','',0))
latest_query=next(q for q in queries if q.startswith('SELECT * FROM sync_checkpoints WHERE algorithm=2 ORDER BY'))
metadata_query=next(q for q in queries if q.startswith('SELECT state FROM sync_checkpoints WHERE time=-1'))
assert db.execute(latest_query).fetchone()[0]>=0
assert db.execute(metadata_query).fetchone()[0]==metadata
db.execute(compact,dict(before=999999999))
assert db.execute(metadata_query).fetchone()[0]==metadata
print('PASS: replay metadata survives maintenance and cannot be decoded as an engine checkpoint')
db.commit()
raw_before=db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]
try:
 with db:
  db.execute('INSERT INTO usage_events VALUES (?,?,?,?,?,?,?,?,?,?)',('late-event',10,'RESUME','fixture','A',None,'OWN_WINDOW','UTC','LOCAL',None))
  db.execute('UPDATE sync_checkpoints SET state=? WHERE time=-1',('{"ready":true,"dirtyFrom":null}',))
  raise RuntimeError('interrupted sync')
except RuntimeError:pass
assert db.execute(metadata_query).fetchone()[0]==metadata
assert db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]==raw_before
print('PASS: interrupted incremental sync cannot clear the committed dirty boundary')
with db:
 db.execute('INSERT INTO usage_events VALUES (?,?,?,?,?,?,?,?,?,?)',('late-event',10,'RESUME','fixture','A',None,'OWN_WINDOW','UTC','LOCAL',None))
 db.execute('UPDATE sync_checkpoints SET state=? WHERE time=-1',('{"ready":true,"dirtyFrom":10}',))
assert db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]==raw_before+1
assert json.loads(db.execute(metadata_query).fetchone()[0])['dirtyFrom']==10
print('PASS: late own evidence and its durable replay boundary commit together')

# Hour and month layers behave like the day cache: exact-bound reads, mirrored invalidation, whole-bucket pruning.
hour_read=next(q for q in maintenance_queries if q.startswith('SELECT * FROM hour_usage WHERE hourStart='))
month_read=next(q for q in maintenance_queries if q.startswith('SELECT * FROM month_usage WHERE monthStart='))
delete_hours=next(q for q in maintenance_queries if q.startswith('DELETE FROM hour_usage WHERE hourEnd>'))
delete_months=next(q for q in maintenance_queries if q.startswith('DELETE FROM month_usage WHERE monthEnd>'))
trim_hours=next(q for q in maintenance_queries if q.startswith('DELETE FROM hour_usage WHERE algorithm!=3'))
trim_months=next(q for q in maintenance_queries if q.startswith('DELETE FROM month_usage WHERE algorithm!=3'))
DailyMetrics_SUMMARY='__QUALITY_SUMMARY__'
hour=lambda ident,start,end,app,evidence:db.execute('INSERT OR REPLACE INTO hour_usage VALUES (?,?,?,?,?,?,?,?,?,?)',(ident,start,end,'UTC','PRIMARY',app,evidence,end-start,0,3))
db.execute('DELETE FROM hour_usage')
hour('h0',0,3600000,'fixture.app','CONFIRMED');hour('h0s',0,1000,DailyMetrics_SUMMARY,'UNKNOWN');hour('h1',3600000,7200000,'fixture.app','CONFIRMED')
assert len(db.execute(hour_read,dict(start=0,end=3600000,zone='UTC',metric='PRIMARY')).fetchall())==1
assert db.execute(hour_read,dict(start=0,end=1000,zone='UTC',metric='PRIMARY')).fetchone()[2]==1000
db.execute(delete_hours,dict(start=3600000))
assert db.execute('SELECT COUNT(*) FROM hour_usage').fetchone()[0]==2
assert db.execute(hour_read,dict(start=3600000,end=7200000,zone='UTC',metric='PRIMARY')).fetchone() is None
for ident,start in [('h2',7200000),('h3',10800000)]:
 db.execute('INSERT OR REPLACE INTO hour_usage VALUES (?,?,?,?,?,?,?,?,?,?)',(ident,start,start+3600000,'UTC','PRIMARY','__QUALITY_SUMMARY__','UNKNOWN',3600000,0,3))
db.execute(trim_hours,dict(zone='UTC',maxRows=2))
assert db.execute('SELECT DISTINCT hourStart FROM hour_usage ORDER BY hourStart').fetchall()==[(7200000,),(10800000,)]
print('PASS: hour cache reads exact bounds, mirrors day invalidation and prunes whole hours only')
db.execute('INSERT OR REPLACE INTO month_usage VALUES (?,?,?,?,?,?,?,?,?,?,?)',('m0',0,2592000000,'UTC','PRIMARY','fixture.app','CONFIRMED',1000,0,21,3))
db.execute('INSERT OR REPLACE INTO month_usage VALUES (?,?,?,?,?,?,?,?,?,?,?)',('m1',2592000000,5184000000,'UTC','PRIMARY','fixture.app','CONFIRMED',2000,0,30,3))
assert db.execute(month_read,dict(start=0,end=2592000000,zone='UTC',metric='PRIMARY')).fetchone()[9]==21
db.execute(delete_months,dict(start=2592000000))
assert db.execute('SELECT COUNT(*) FROM month_usage').fetchone()[0]==1
db.execute(trim_months,dict(zone='UTC',maxRows=1))
assert db.execute('SELECT COUNT(*) FROM month_usage').fetchone()[0]==1
print('PASS: month cache keeps recorded days, mirrors day invalidation and prunes by whole months')

# Two-phase collection: raw evidence and dirty boundary commit before derived statistics, which retry from local rows.
derived_before=db.execute('SELECT COUNT(*) FROM usage_intervals').fetchone()[0]
events_before=db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]
with db:
 db.execute('INSERT OR IGNORE INTO usage_events VALUES (?,?,?,?,?,?,?,?,?,?)',('phase-a',5000,'RESUME','fixture.app','A',None,'USAGE_EVENTS','UTC','LOCAL',None))
 db.execute('UPDATE sync_checkpoints SET state=? WHERE time=-1',('{"format":"usage-replay-progress","version":1,"ready":true,"dirtyFrom":5000}',))
try:
 with db:
  db.execute('DELETE FROM usage_intervals WHERE start>=5000 AND algorithm=2')
  raise RuntimeError('statistics failed after raw commit')
except RuntimeError:pass
assert db.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]==events_before+1
assert json.loads(db.execute(metadata_query).fetchone()[0])['dirtyFrom']==5000
assert db.execute('SELECT COUNT(*) FROM usage_intervals').fetchone()[0]==derived_before
with db:
 db.execute('INSERT OR REPLACE INTO usage_intervals VALUES (?,?,?,?,?,?,?,?,?,?,?)',('phase-b','PRIMARY','fixture.app',5000,6000,'CONFIRMED','EXPLICIT_WINDOW_FOCUS','USAGE_EVENTS','phase-a',0,2))
 db.execute('UPDATE sync_checkpoints SET state=? WHERE time=-1',('{"format":"usage-replay-progress","version":1,"ready":true,"dirtyFrom":null}',))
assert json.loads(db.execute(metadata_query).fetchone()[0])['dirtyFrom'] is None
assert db.execute('SELECT COUNT(*) FROM usage_intervals').fetchone()[0]==derived_before+1
print('PASS: two-phase collect keeps raw events and dirty boundary when statistics fail, then completes from local evidence')

future_query=next(q for q in queries if q.startswith('SELECT MIN(time) FROM usage_events WHERE time>='))
db.execute('SAVEPOINT future_fixture')
for identity,time,signal,source in [('future-other',9000,'OTHER','USAGE_EVENTS'),('future-archive',9000,'RESUME','LEGACY_IMPORT'),('future-own',10000,'RESUME','OWN_WINDOW')]:
 db.execute('INSERT INTO usage_events VALUES (?,?,?,?,?,?,?,?,?,?)',(identity,time,signal,'fixture.app','A',None,source,'UTC','LOCAL',None))
assert db.execute(future_query,dict(start=9000)).fetchone()[0]==10000
assert db.execute(future_query,dict(start=10000)).fetchone()[0]==10000
assert db.execute(future_query,dict(start=10001)).fetchone()[0] is None
db.execute('ROLLBACK TO future_fixture');db.execute('RELEASE future_fixture')
print('PASS: replay completion retains unconsumed boundary and future local events, excluding archives and OTHER')
