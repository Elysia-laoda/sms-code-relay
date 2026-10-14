import sqlite3, sys, datetime
db = sys.argv[1]
con = sqlite3.connect(db)
cur = con.cursor()
print("== msg 表 ==")
rows = cur.execute("SELECT id, created_at, sender, sim_label, matched, match_reason, code, forwarded, attempts, next_attempt_at, last_error, sink, response FROM msg ORDER BY id").fetchall()
hdr = ["id", "time", "from", "sim", "命中", "理由", "code", "已发", "试", "next_at", "err", "sink", "resp"]
print(" | ".join(hdr))
for r in rows:
    t = datetime.datetime.fromtimestamp(r[1] / 1000).strftime("%H:%M:%S")
    nxt = ""
    if r[9]:
        nxt = datetime.datetime.fromtimestamp(r[9] / 1000).strftime("%H:%M:%S")
    print(" | ".join([str(r[0]), t, str(r[2]), str(r[3]), "T" if r[4] else "F", str(r[5]), str(r[6]), "T" if r[7] else "F", str(r[8]), nxt, str(r[10]), str(r[11]), str(r[12])[:40]]))
print()
print("== 合计 ==")
print("总收:", cur.execute("SELECT COUNT(*) FROM msg").fetchone()[0])
print("命中:", cur.execute("SELECT COUNT(*) FROM msg WHERE matched=1").fetchone()[0])
print("已发:", cur.execute("SELECT COUNT(*) FROM msg WHERE forwarded=1").fetchone()[0])
print()
print("== log 表（最后 25 条）==")
for r in cur.execute("SELECT ts, level, msg FROM log ORDER BY id DESC LIMIT 25").fetchall():
    t = datetime.datetime.fromtimestamp(r[0] / 1000).strftime("%H:%M:%S")
    print(t, "[" + str(r[1]) + "]", str(r[2])[:150])
