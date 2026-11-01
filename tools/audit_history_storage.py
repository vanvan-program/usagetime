"""Isolated synthetic SQLite migration, crash, capacity and production-query audit.

Run: python tools/audit_history_storage.py
Only creates files under .qa-temp/history-audit-<uuid>; never opens a user DB.
Host SQLite evidence is not Android Room/device or physical power-loss evidence.
"""
from pathlib import Path
import datetime as dt
import hashlib
import json
import os
import re
import sqlite3
import statistics
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/dev.usagetime.app.data.UsageDatabase"
DUAL = (ROOT / "app/src/main/java/dev/usagetime/app/data/DualStorage.kt").read_text(encoding="utf-8")
HISTORY = (ROOT / "app/src/main/java/dev/usagetime/app/data/RetentionStorage.kt").read_text(encoding="utf-8")
DAY = 86_400_000
METRICS = ("PRIMARY", "VISIBLE")
DELETE_EVENTS = re.search(r'const val DELETE_EVENTS="([^"]+)"', HISTORY)[1]
DELETE_SLICES = re.search(r'const val DELETE_SLICES="([^"]+)"', HISTORY)[1]
DAILY_READ = re.search(r'@Query\("(SELECT \* FROM history_days[^"]+)"', HISTORY)[1]


def schema(version):
    return json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]


def create(db, version):
    for table in schema(version)["entities"]:
        db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
        for index in table.get("indices",[]):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
    db.execute(f"PRAGMA user_version={version}")


def migrations():
    # Extract exact production statements, not a separately maintained schema.
    blocks = re.split(r"val statements(?:V([345]))?=listOf\(", DUAL)[1:]
    result = {}
    for i in range(0, len(blocks), 2):
        version = int(blocks[i] or 2)
        block = blocks[i + 1].split("\n    )", 1)[0]
        result[version] = re.findall(r'^        "([^"\n]+)"', block, re.M)
    result[6] = re.findall(r'^        "([^"\n]+)"', HISTORY.split("val statements=listOf(", 1)[1].split("\n    )", 1)[0], re.M)
    return result


def verify_schema(db):
    for table in schema(6)["entities"]:
        name = table["tableName"]
        info = db.execute(f'PRAGMA table_info("{name}")').fetchall()
        actual = {r[1]: (r[2], bool(r[3])) for r in info}
        expected = {f["columnName"]: (f["affinity"], f.get("notNull",False)) for f in table["fields"]}
        assert actual == expected, name
        keys = [r[1] for r in sorted(info, key=lambda r: r[5]) if r[5]]
        assert keys == table["primaryKey"]["columnNames"], (name, keys)
        for index in table.get("indices",[]):
            cols = [r[2] for r in db.execute(f'PRAGMA index_info("{index["name"]}")')]
            assert cols == index["columnNames"], (name, cols)
            flags = {r[1]: bool(r[2]) for r in db.execute(f'PRAGMA index_list("{name}")')}
            assert flags[index["name"]] == index["unique"]


def migration_tests():
    statements = migrations()
    results = []
    for version in range(1, 6):
        db = sqlite3.connect(":memory:")
        create(db, version)
        preserved = {}
        for table in schema(version)["entities"]:
            name = table["tableName"]
            values = [None if not f.get("notNull",False) else (1000 if f["affinity"] == "INTEGER" else "fixture") for f in table["fields"]]
            db.execute(f'INSERT INTO "{name}" VALUES({",".join("?" for _ in values)})', values)
            preserved[name] = db.execute(f'SELECT * FROM "{name}"').fetchall()
        db.commit()
        for target in range(version + 1, 7):
            with db:
                for statement in statements[target]:
                    db.execute(statement)
                db.execute(f"PRAGMA user_version={target}")
        verify_schema(db)
        for name, rows in preserved.items():
            assert db.execute(f'SELECT * FROM "{name}"').fetchall() == rows
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        results.append(f"v{version}->v6: all existing tables/values preserved; columns, primary keys and indexes match exported Room schema")
        db.close()
    return results


def interval_queries_test():
    queries=re.findall(r'@Query\("(SELECT \* FROM usage_intervals INDEXED BY[^"]+)"',DUAL)
    assert len(queries)==2
    db=sqlite3.connect(":memory:")
    create(db,6)
    spans=[(0,DAY),(DAY-1000,DAY+1000),(DAY,DAY+1000),(DAY+1000,DAY+2000),(0,100*DAY)]
    for i,(a,b) in enumerate(spans):
        for metric in METRICS:
            db.execute("INSERT INTO usage_intervals VALUES(?,?,?,?,?,'INFERRED','TEST','TEST','',0,2)",(f"{metric}:{i}",metric,"fixture",a,b))
    checked=0
    for a,b in ((0,1),(DAY,DAY+1),(DAY-1,DAY),(DAY+1000,DAY+2000),(99*DAY,100*DAY)):
        for metric in METRICS:
            args={"start":a,"end":b,"metric":metric}
            rows=[db.execute(q,args).fetchall() for q in queries]
            expected={f"{metric}:{i}" for i,(x,y) in enumerate(spans) if y>a and x<b}
            assert {r[0] for r in rows[0]}=={r[0] for r in rows[1]}==expected
            checked+=1
    db.close()
    return f"{checked} half-open overlap/metric/cross-day/long-span cases: both production index paths agree"


def digest(rows):
    def encode(value):
        text = str(value)
        return f"{len(text)}:{text}"
    parts = []
    for r in sorted(rows, key=lambda r: (r[3], r[4])):
        parts.append("".join(encode(v) for v in (r[0], r[1], r[2], r[3], r[4], r[5], r[7], r[8], r[9])))
    return hashlib.sha256("\n".join(parts).encode()).hexdigest()


def insert_day(db, day, apps):
    for metric in METRICS:
        rows = [("UTC", day, metric, "1" + app, "INFERRED", day + DAY, app, 60_000, 0, 3) for app in apps]
        rows += [("UTC", day, metric, "1__QUALITY_SUMMARY__", "UNKNOWN", day + DAY, "__QUALITY_SUMMARY__", DAY - len(apps) * 60_000, 0, 3),
                 ("UTC", day, metric, "1__CONFIRMED_COVERAGE__", "CONFIRMED", day + DAY, "__CONFIRMED_COVERAGE__", 0, 0, 3)]
        db.executemany("INSERT OR REPLACE INTO history_days VALUES(?,?,?,?,?,?,?,?,?,?)", rows)
        db.execute("INSERT OR REPLACE INTO history_seals VALUES(?,?,?,?,?,?,?)", ("UTC", day, metric, day + DAY, len(rows), digest(rows), "LOCAL"))


def safe_file(path):
    path = Path(path).resolve()
    assert path.parent.parent == (ROOT / ".qa-temp").resolve()
    assert path.parent.name.startswith("history-audit-")
    assert path.suffix == ".sqlite"
    return path


def crash_child(path, stage):
    db = sqlite3.connect(safe_file(path))
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=FULL")
    db.execute("BEGIN IMMEDIATE")
    insert_day(db, 0, ["fixture.app"])
    if stage == "archive_uncommitted":
        os._exit(19)
    db.commit()
    if stage == "archive_committed":
        os._exit(19)
    db.execute("BEGIN IMMEDIATE")
    db.execute(DELETE_EVENTS, {"floor": DAY})
    if stage == "events_deleted":
        os._exit(19)
    db.execute(DELETE_SLICES, {"floor": DAY})
    if stage == "slices_deleted":
        os._exit(19)
    db.execute("INSERT INTO retention_state VALUES(0,?,?)", (DAY, "UTC"))
    if stage == "floor_written":
        os._exit(19)
    db.commit()
    os._exit(19)


def crash_tests(out):
    results = []
    for stage in ("archive_uncommitted", "archive_committed", "events_deleted", "slices_deleted", "floor_written", "committed"):
        path = out / f"crash-{stage}.sqlite"
        db = sqlite3.connect(path)
        create(db, 6)
        for identity, at in (("before", DAY - 1), ("boundary", DAY), ("after", DAY + 1)):
            db.execute("INSERT INTO usage_events VALUES(?,?,'RESUME','fixture','A',NULL,'USAGE_EVENTS','UTC','LOCAL',NULL)", (identity, at))
        db.execute("INSERT INTO usage_events VALUES('imported',0,'RESUME','fixture','A',NULL,'LEGACY_IMPORT','UTC','LOCAL',NULL)")
        db.execute("INSERT INTO usage_intervals VALUES('old','PRIMARY','fixture',0,?,'INFERRED','TEST','TEST','',0,2)", (DAY,))
        db.execute("INSERT INTO usage_intervals VALUES('recent','PRIMARY','fixture',?,?,'INFERRED','TEST','TEST','',0,2)", (DAY, DAY + 1))
        db.commit()
        db.close()
        result = subprocess.run([sys.executable, __file__, "--crash", str(path), stage], capture_output=True)
        assert result.returncode == 19, result.stderr
        db = sqlite3.connect(path)
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        committed = stage == "committed"
        assert db.execute("SELECT COUNT(*) FROM usage_events WHERE id='before'").fetchone()[0] == (0 if committed else 1)
        assert db.execute("SELECT COUNT(*) FROM usage_intervals WHERE id='old'").fetchone()[0] == (0 if committed else 1)
        assert db.execute("SELECT COUNT(*) FROM retention_state").fetchone()[0] == int(committed)
        assert db.execute("SELECT COUNT(*) FROM usage_events WHERE id IN ('boundary','after','imported')").fetchone()[0] == 3
        assert db.execute("SELECT COUNT(*) FROM history_seals").fetchone()[0] == (0 if stage == "archive_uncommitted" else 2)
        if committed:
            with db:
                db.execute(DELETE_EVENTS, {"floor": DAY})
                db.execute(DELETE_SLICES, {"floor": DAY})
            assert db.execute("SELECT COUNT(*) FROM usage_intervals").fetchone()[0] == 1
        results.append(f"{stage}: forced process exit, reopen integrity OK; atomic floor/deletion; cutoff boundary and imported evidence retained")
        db.close()
    return results


def timed(fn, repeats=21):
    samples = []
    for _ in range(repeats):
        started = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - started) * 1000)
    ordered = sorted(samples)
    return {"median_ms": round(statistics.median(samples), 3), "p95_ms": round(ordered[min(len(ordered)-1, int(len(ordered)*.95))], 3), "runs": repeats}


def archive_only_bytes(db, out, apps):
    # dbstat is not available in every bundled SQLite. Measure a real archive-only fixture instead.
    path=out/f"archive-only-{apps}apps.sqlite"
    if path.exists():
        raise RuntimeError(f"Refusing to overwrite an existing fixture: {path}")
    archive=sqlite3.connect(path)
    create(archive,6)
    for table in ("history_days","history_seals"):
        cursor=db.execute(f"SELECT * FROM {table}")
        while True:
            rows=cursor.fetchmany(1000)
            if not rows:
                break
            archive.executemany(f"INSERT INTO {table} VALUES({','.join('?' for _ in rows[0])})",rows)
    archive.commit()
    assert archive.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
    archive.close()
    return path.stat().st_size


def benchmark(out, app_count, sessions):
    path = out / f"capacity-{app_count}apps.sqlite"
    db = sqlite3.connect(path)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=FULL")
    create(db, 6)
    start = int(dt.datetime(2016, 11, 1, tzinfo=dt.timezone.utc).timestamp()*1000)
    end = int(dt.datetime(2026, 11, 1, tzinfo=dt.timezone.utc).timestamp()*1000)
    days = (end - start)//DAY
    apps = [f"org.synthetic.application{i:03}" for i in range(app_count)]
    app_set=set(apps)
    started = time.perf_counter()
    for i in range(days):
        insert_day(db, start + i*DAY, apps)
        if i % 100 == 0:
            db.commit()
    db.commit()
    # 90 complete days of detail; two metrics per session and two raw events per session.
    for i in range(90):
        day = end - (90-i)*DAY
        events, slices = [], []
        for j in range(sessions):
            at = day + j*(DAY//sessions)
            app = apps[j % len(apps)]
            ident = hashlib.sha256(f"{i}:{j}".encode()).hexdigest()
            for signal, event_time in (("RESUME", at), ("PAUSE", at+1000)):
                eid = hashlib.sha256(f"{ident}:{signal}".encode()).hexdigest()
                events.append((eid,event_time,signal,app,app+".MainActivity",None,"USAGE_EVENTS","UTC","LOCAL",None))
            for metric in METRICS:
                sid = hashlib.sha256(f"{ident}:{metric}".encode()).hexdigest()
                slices.append((sid,metric,app,at,at+1000,"INFERRED","SYNTHETIC_CAPACITY","USAGE_EVENTS",ident+",proof:"+ident,0,2))
        db.executemany("INSERT INTO usage_events VALUES(?,?,?,?,?,?,?,?,?,?)",events)
        db.executemany("INSERT INTO usage_intervals VALUES(?,?,?,?,?,?,?,?,?,?,?)",slices)
        db.commit()
    db.execute("ANALYZE")
    db.commit()
    db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    build_seconds = time.perf_counter() - started
    args = {"zone":"UTC","start":end-91*DAY,"metric":"PRIMARY"}
    day_plan = [r[3] for r in db.execute("EXPLAIN QUERY PLAN "+DAILY_READ,args)]
    assert any("INDEX" in p for p in day_plan)
    def daily():
        rows = db.execute(DAILY_READ,args).fetchall()
        assert len(rows)==app_count+2
    def history(days_back, validate=False):
        total = 0
        for day in range(days-days_back, days):
            for metric in METRICS:
                rows=db.execute(DAILY_READ,{"zone":"UTC","start":start+day*DAY,"metric":metric}).fetchall()
                assert len(rows)==app_count+2
                if validate:
                    seal=db.execute("SELECT rows,digest FROM history_seals WHERE zone=? AND dayStart=? AND metric=?",("UTC",start+day*DAY,metric)).fetchone()
                    assert seal[0]==len(rows) and seal[1]==digest(rows)
                total += sum(r[7] for r in rows if r[6] in app_set)
        assert total==days_back*2*app_count*60_000
    slice_query=re.search(r'@Query\("(SELECT \* FROM usage_intervals INDEXED BY index_usage_intervals_metric_end_start[^"]+)"',DUAL)[1]
    slice_args={"start":end-DAY,"end":end,"metric":"PRIMARY"}
    slice_plan=[r[3] for r in db.execute("EXPLAIN QUERY PLAN "+slice_query,slice_args)]
    queries={
        "one_day_archive":timed(daily),
        "one_year_both_metrics_sql_daily_loop":timed(lambda:history(365),3),
        "ten_years_both_metrics_sql_daily_loop":timed(lambda:history(days),3),
        "ten_years_both_metrics_with_python_digest_validation":timed(lambda:history(days,True),3),
        "recent_day_detail":timed(lambda:db.execute(slice_query,slice_args).fetchall()),
        "recent_day_detail_before_fix":timed(lambda:db.execute(slice_query.replace(" INDEXED BY index_usage_intervals_metric_end_start"," INDEXED BY index_usage_intervals_metric_start"),slice_args).fetchall(),5),
        "oldest_retained_day_detail_start_edge":timed(lambda:db.execute(slice_query.replace(" INDEXED BY index_usage_intervals_metric_end_start"," INDEXED BY index_usage_intervals_metric_start"),{"start":end-90*DAY,"end":end-89*DAY,"metric":"PRIMARY"}).fetchall()),
    }
    # Optional range aggregate measures the index's capacity, not current app behavior.
    aggregate="SELECT metric,app,evidence,SUM(millis) FROM history_days WHERE zone=? AND dayStart>=? AND dayStart<? GROUP BY metric,app,evidence"
    queries["ten_year_range_sql_potential_only"]=timed(lambda:db.execute(aggregate,("UTC",start,end)).fetchall(),5)
    size = path.stat().st_size
    counts={t:db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("history_days","history_seals","usage_events","usage_intervals")}
    try:
        pages={name: n for name,n in db.execute("SELECT name,SUM(pgsize) FROM dbstat GROUP BY name")}
    except sqlite3.OperationalError:
        pages={}
    # JSONL here uses exact SQLite column names and values, then gzip; production JSON has additional table envelopes.
    import gzip
    backup_path=out/f"capacity-{app_count}apps.jsonl.gz"
    with gzip.open(backup_path,"wt",encoding="utf-8") as stream:
        for table in counts:
            cursor=db.execute(f"SELECT * FROM {table}")
            names=[d[0] for d in cursor.description]
            for row in cursor:
                stream.write(json.dumps(dict(zip(names,row)),separators=(",",":"))+"\n")
    archive_bytes=archive_only_bytes(db,out,app_count)
    db.close()
    return {"apps_per_day":app_count,"sessions_per_day":sessions,"calendar_days":days,"retained_detail_days":90,
            "counts":counts,"database_bytes":size,"gzip_jsonl_bytes":backup_path.stat().st_size,
            "build_seconds":round(build_seconds,2),"queries":queries,"archive_query_plan":day_plan,
            "detail_query_plan":slice_plan,"page_bytes_by_object":pages,
            "ten_year_archive_only_measured_bytes":archive_bytes,
            "five_year_archive_estimate_bytes":round(archive_bytes*.5)}


def finalize(path):
    path=Path(path).resolve()
    out=path.parent
    assert out.parent==(ROOT/".qa-temp").resolve() and out.name.startswith("history-audit-")
    result=json.loads(path.read_text(encoding="utf-8"))
    result["interval_queries_test"]=interval_queries_test()
    end=int(dt.datetime(2026,11,1,tzinfo=dt.timezone.utc).timestamp()*1000)
    query=re.search(r'@Query\("(SELECT \* FROM usage_intervals INDEXED BY index_usage_intervals_metric_start[^"]+)"',DUAL)[1]
    for b in result["benchmarks"]:
        db=sqlite3.connect(safe_file(out/f"capacity-{b['apps_per_day']}apps.sqlite"))
        archive_bytes=archive_only_bytes(db,out,b["apps_per_day"])
        b["ten_year_archive_only_measured_bytes"]=archive_bytes
        b["five_year_archive_estimate_bytes"]=round(archive_bytes*.5)
        b["queries"]["oldest_retained_day_detail_start_edge"]=timed(lambda:db.execute(query,{"start":end-90*DAY,"end":end-89*DAY,"metric":"PRIMARY"}).fetchall())
        db.close()
    path.write_text(json.dumps(result,indent=2),encoding="utf-8")
    print(f"FINALIZED: {path}")


def main():
    out=ROOT/".qa-temp"/f"history-audit-{uuid.uuid4().hex}"
    out.mkdir(parents=True)
    result={"generated_at":dt.datetime.now(dt.timezone.utc).isoformat(),"sqlite_version":sqlite3.sqlite_version,
            "output":str(out),"method":"Synthetic data only; host SQLite; WAL+FULL; warm OS-cache query timings; no Android/device claims",
            "migration_tests":migration_tests(),"interval_queries_test":interval_queries_test(),"crash_tests":crash_tests(out),"benchmarks":[]}
    print("PASS migrations: 5 upgrade entry points; PASS process-crash recovery: 6 stages",flush=True)
    for apps,sessions in ((20,1000),(100,3000)):
        print(f"Running ten-year profile: {apps} apps/day, {sessions} sessions/day",flush=True)
        result["benchmarks"].append(benchmark(out,apps,sessions))
        (out/"results.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
        print(json.dumps({k:v for k,v in result["benchmarks"][-1].items() if k in ("apps_per_day","counts","database_bytes","queries")}),flush=True)
    print(f"RESULTS: {out/'results.json'}",flush=True)


if __name__=="__main__":
    if len(sys.argv)>1 and sys.argv[1]=="--crash":
        crash_child(sys.argv[2],sys.argv[3])
    elif len(sys.argv)>1 and sys.argv[1]=="--finalize":
        finalize(sys.argv[2])
    else:
        main()
