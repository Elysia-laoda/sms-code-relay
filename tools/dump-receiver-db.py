import sqlite3, sys, datetime
con = sqlite3.connect(sys.argv[1])
cur = con.cursor()
print("== code 表 ==")
rows = cur.execute("SELECT id, ts, source, sender, sim_label, code, body, device, copied FROM code ORDER BY id").fetchall()
for r in rows:
    t = datetime.datetime.fromtimestamp(r[1] / 1000).strftime("%H:%M:%S")
    print(f"#{r[0]} {t} [{r[2]}] {r[4]} {r[3]} code={r[5]} copied={r[8]}")
    print(f"     body={r[6][:60]}")
print("总条数:", cur.execute("SELECT COUNT(*) FROM code").fetchone()[0])
print()
print("== log 表（最后 15 条）==")
for r in cur.execute("SELECT ts, level, msg FROM log ORDER BY id DESC LIMIT 15").fetchall():
    t = datetime.datetime.fromtimestamp(r[0] / 1000).strftime("%H:%M:%S")
    print(t, "[" + str(r[1]) + "]", str(r[2])[:110])
