#!/usr/bin/env python3
"""Kaip — веб-панель для запуска десктопных программ (exe/msi/deb/AppImage) на сервере.

Программы рисуются на виртуальном экране Xvnc (:1), который виден в браузере через noVNC.
Панель хранит список программ в /data/apps.json, а общую с телефоном папку — в /data/shared.
"""
import hmac
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid
from datetime import timedelta
from pathlib import Path

import psutil
from flask import Flask, abort, jsonify, redirect, request, send_file, send_from_directory, session

DATA = Path(os.environ.get("KAIP_DATA", "/data"))
SHARED = DATA / "shared"
PROGRAMS = DATA / "programs"
DEBS = DATA / "debs"
DOWNLOADS = DATA / "downloads"
APP_LOGS = DATA / "logs" / "apps"
APPS_FILE = DATA / "apps.json"
HOME = Path("/home/user")
APPIMAGES = HOME / "Applications"
WINEPREFIX = HOME / ".wine"
USER, UID, GID = "user", 1000, 1000
DISPLAY = ":1"
STATIC = Path(__file__).parent / "static"

PASSWORD = os.environ.get("KAIP_PASSWORD", "")
HTTPS = os.environ.get("KAIP_HTTPS", "1") == "1"

# Процессы рабочего стола и служебные процессы Wine — их не показываем в списке «Запущено».
SESSION_PROCS = {
    "Xvnc", "openbox", "tint2", "websockify", "dbus-daemon", "dbus-launch", "at-spi-bus-laun",
    "wineserver", "wineserver64", "wine64-preloader", "services.exe", "winedevice.exe", "plugplay.exe", "explorer.exe", "rpcss.exe",
    "svchost.exe", "conhost.exe", "start.exe", "tabtip.exe", "rundll32.exe", "wineboot.exe",
    "winemenubuilder", "winemenubuilder.exe", "mscorsvw.exe", "xset", "bash", "sh", "sleep",
}
RESOLUTIONS = ["960x540", "1280x720", "1366x768", "1600x900", "1920x1080"]

app = Flask(__name__, static_folder=None)
app.secret_key = os.environ.get("KAIP_SECRET") or secrets.token_hex(32)
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=HTTPS,
    PERMANENT_SESSION_LIFETIME=timedelta(days=30),
)

apps_lock = threading.Lock()
apt_lock = threading.Lock()
JOBS = {}
LOGIN_FAILS = {}
_apt_updated = False


# ---------------------------------------------------------------- helpers

def chown(path):
    try:
        os.chown(path, UID, GID, follow_symlinks=False)
    except OSError:
        pass


def clean_name(name):
    name = Path(name or "").name
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip(" .")
    return name or "file"


def unique_path(folder, name):
    folder.mkdir(parents=True, exist_ok=True)
    p = folder / name
    stem, suffix, i = p.stem, p.suffix, 1
    while p.exists():
        p = folder / f"{stem} ({i}){suffix}"
        i += 1
    return p


def load_apps():
    try:
        return json.loads(APPS_FILE.read_text())
    except (OSError, ValueError):
        return []


def save_apps(apps):
    tmp = APPS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(apps, ensure_ascii=False, indent=1))
    tmp.replace(APPS_FILE)


def add_app(name, cmd, kind, **extra):
    with apps_lock:
        apps = load_apps()
        for a in apps:
            if a["cmd"] == cmd:
                return a
        entry = {"id": uuid.uuid4().hex[:10], "name": name, "cmd": cmd, "type": kind,
                 "added": int(time.time()), **extra}
        apps.append(entry)
        save_apps(apps)
        return entry


def user_env():
    return {
        "DISPLAY": DISPLAY, "HOME": str(HOME), "USER": USER, "LOGNAME": USER,
        "PATH": "/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games",
        "LANG": "ru_RU.UTF-8", "LC_ALL": "ru_RU.UTF-8",
        "XDG_RUNTIME_DIR": "/tmp/runtime-user",
        "WINEPREFIX": str(WINEPREFIX), "WINEDEBUG": "-all",
        "APPIMAGE_EXTRACT_AND_RUN": "1", "NO_AT_BRIDGE": "1",
    }


def as_user(argv, **kw):
    env = [f"{k}={v}" for k, v in user_env().items()]
    return ["runuser", "-u", USER, "--", "env", "-i", *env, *argv]


def ensure_wine_drive():
    """Папка телефона видна в Windows-программах как диск P:."""
    dosdevices = WINEPREFIX / "dosdevices"
    link = dosdevices / "p:"
    if dosdevices.is_dir() and not link.exists():
        try:
            link.symlink_to(SHARED)
            chown(link)
        except OSError:
            pass


def launch(cmd, name):
    argv = shlex.split(cmd)
    if not argv:
        raise ValueError("Пустая команда")
    if argv[0] in ("wine", "wine64", "winecfg", "msiexec"):
        ensure_wine_drive()
    APP_LOGS.mkdir(parents=True, exist_ok=True)
    log_path = APP_LOGS / (clean_name(name) + ".log")
    log = open(log_path, "ab")
    log.write(f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} $ {cmd}\n".encode())
    log.flush()
    proc = subprocess.Popen(as_user(argv), stdout=log, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, cwd=str(HOME), start_new_session=True)
    log.close()
    threading.Thread(target=proc.wait, daemon=True).start()  # не оставляем зомби
    return proc.pid


# ---------------------------------------------------------------- background jobs

def new_job(title):
    job_id = uuid.uuid4().hex[:8]
    JOBS[job_id] = {"id": job_id, "title": title, "status": "running", "log": [], "started": time.time()}
    return JOBS[job_id]


def job_log(job, line):
    job["log"].append(line.rstrip())
    del job["log"][:-300]


def job_run(job, argv, **kw):
    job_log(job, "$ " + " ".join(argv))
    p = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                         env={**os.environ, "DEBIAN_FRONTEND": "noninteractive"}, **kw)
    for line in p.stdout:
        job_log(job, line)
    if p.wait() != 0:
        raise RuntimeError(f"команда завершилась с кодом {p.returncode}")


def run_job(job, fn, *args):
    def runner():
        try:
            result = fn(job, *args)
            job["status"] = "done"
            if result:
                job_log(job, str(result))
        except Exception as e:  # noqa: BLE001 — показываем любую ошибку пользователю
            job["status"] = "error"
            job_log(job, f"ОШИБКА: {e}")
        job["finished"] = time.time()
        old = sorted(JOBS.values(), key=lambda j: j["started"])[:-20]
        for j in old:
            JOBS.pop(j["id"], None)
    threading.Thread(target=runner, daemon=True).start()
    return job


def deb_package(path):
    return subprocess.check_output(["dpkg-deb", "-f", str(path), "Package"], text=True).strip()


def parse_desktop_file(path):
    name = exec_ = None
    in_entry = False
    try:
        lines = Path(path).read_text(errors="ignore").splitlines()
    except OSError:
        return None
    for line in lines:
        line = line.strip()
        if line.startswith("["):
            in_entry = line == "[Desktop Entry]"
            continue
        if not in_entry or "=" not in line:
            continue
        key, val = line.split("=", 1)
        if key == "Name":
            name = val
        elif key == "Exec":
            exec_ = val
        elif key in ("NoDisplay", "Hidden") and val.lower() == "true":
            return None
        elif key == "Type" and val != "Application":
            return None
    if not exec_:
        return None
    exec_ = re.sub(r"\s*%[fFuUdDnNickvm]", "", exec_).strip()
    return name or Path(path).stem, exec_


def install_deb(job, path):
    global _apt_updated
    pkg = deb_package(path)
    job_log(job, f"Пакет: {pkg}")
    with apt_lock:
        if not _apt_updated:
            try:
                job_run(job, ["apt-get", "update"])
                _apt_updated = True
            except RuntimeError:
                job_log(job, "Не удалось обновить список пакетов — пробую установить так.")
        job_run(job, ["apt-get", "install", "-y", "--no-install-recommends", str(path)])
    files = subprocess.check_output(["dpkg", "-L", pkg], text=True).splitlines()
    electron = any(f.endswith("/chrome-sandbox") for f in files)
    added = []
    for f in files:
        if f.endswith(".desktop") and "/applications/" in f:
            parsed = parse_desktop_file(f)
            if parsed:
                name, cmd = parsed
                if electron and "--no-sandbox" not in cmd:
                    cmd += " --no-sandbox"
                added.append(add_app(name, cmd, "deb", package=pkg)["name"])
    if not added:
        bins = [f for f in files if re.match(r"^/usr/(local/)?(s)?bin/[^/]+$", f)]
        if bins:
            cmd = shlex.quote(bins[0]) + (" --no-sandbox" if electron else "")
            added.append(add_app(pkg, cmd, "deb", package=pkg)["name"])
    if added:
        return "Готово. Добавлено: " + ", ".join(added)
    return "Пакет установлен, но ярлык не найден — добавь команду вручную."


def reinstall_debs(job):
    """deb-пакеты ставятся внутрь контейнера, поэтому после пересборки ставим их заново."""
    missing = []
    for path in sorted(DEBS.glob("*.deb")):
        try:
            pkg = deb_package(path)
        except (subprocess.CalledProcessError, OSError):
            continue
        if subprocess.run(["dpkg", "-s", pkg], capture_output=True).returncode != 0:
            missing.append(path)
    if not missing:
        return "Все deb-пакеты на месте."
    for path in missing:
        install_deb(job, path)
    return f"Переустановлено пакетов: {len(missing)}"


def handle_file(path, original_name):
    """Разбирает загруженный файл по типу. Возвращает описание для пользователя."""
    name = clean_name(original_name)
    low = name.lower()
    stem = Path(name).stem
    if low.endswith((".exe", ".msi", ".bat")):
        dest = unique_path(PROGRAMS / clean_name(stem), name)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(path), dest)
        for p in (PROGRAMS, dest.parent, dest):
            chown(p)
        if low.endswith(".msi"):
            cmd = "wine msiexec /i " + shlex.quote(str(dest))
        elif low.endswith(".bat"):
            cmd = "wine cmd /c " + shlex.quote(str(dest))
        else:
            cmd = "wine " + shlex.quote(str(dest))
        app_ = add_app(stem, cmd, "exe")
        return {"kind": "app", "app": app_,
                "message": f"«{stem}» добавлена. Если это установщик — запусти его, а потом нажми «Найти установленные»."}
    if low.endswith(".deb"):
        dest = unique_path(DEBS, name)
        shutil.move(str(path), dest)
        job = run_job(new_job(f"Установка {name}"), install_deb, dest)
        return {"kind": "job", "job": job["id"], "message": f"Устанавливаю {name}…"}
    if low.endswith(".appimage"):
        dest = unique_path(APPIMAGES, name)
        shutil.move(str(path), dest)
        dest.chmod(0o755)
        chown(dest)
        app_ = add_app(stem, shlex.quote(str(dest)) + " --no-sandbox", "appimage")
        return {"kind": "app", "app": app_, "message": f"«{stem}» добавлена."}
    dest = unique_path(SHARED, name)
    shutil.move(str(path), dest)
    chown(dest)
    return {"kind": "file", "message": f"Это не программа — файл сохранён в общую папку: {dest.name}"}


def download_url(job, url):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError("Нужна ссылка http(s)://")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 Kaip"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        name = None
        cd = resp.headers.get("Content-Disposition", "")
        m = re.search(r"filename\*=UTF-8''([^;]+)", cd) or re.search(r'filename="?([^";]+)"?', cd)
        if m:
            name = urllib.parse.unquote(m.group(1))
        if not name:
            name = urllib.parse.unquote(Path(urllib.parse.urlparse(resp.geturl()).path).name)
        name = clean_name(name)
        total = int(resp.headers.get("Content-Length") or 0)
        tmp = unique_path(DOWNLOADS, name + ".part")
        done, last = 0, 0.0
        with open(tmp, "wb") as f:
            while chunk := resp.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if time.time() - last > 2:
                    last = time.time()
                    pct = f" ({done * 100 // total}%)" if total else ""
                    job_log(job, f"Скачано {done >> 20} МБ{pct}")
    job_log(job, f"Скачано полностью: {name}, {done >> 20} МБ")
    result = handle_file(tmp, name)
    if result["kind"] == "job":
        # Ждём установку deb, чтобы показать итог в этой же задаче.
        sub = JOBS.get(result["job"])
        while sub and sub["status"] == "running":
            time.sleep(1)
        if sub:
            for line in sub["log"][-5:]:
                job_log(job, line)
            if sub["status"] == "error":
                raise RuntimeError("установка не удалась")
        return "Готово."
    return result["message"]


def scan_wine_programs():
    drive_c = WINEPREFIX / "drive_c"
    patterns = [
        "ProgramData/Microsoft/Windows/Start Menu/Programs/**/*.lnk",
        "users/*/AppData/Roaming/Microsoft/Windows/Start Menu/Programs/**/*.lnk",
        "users/*/Desktop/*.lnk",
        "users/Public/Desktop/*.lnk",
    ]
    skip = re.compile(r"uninstall|удал|readme|help|справк|website|сайт|licen", re.I)
    found = []
    seen = set()
    for pattern in patterns:
        for lnk in drive_c.glob(pattern):
            if skip.search(lnk.stem) or lnk.stem.lower() in seen:
                continue
            seen.add(lnk.stem.lower())
            before = len(load_apps())
            add_app(lnk.stem, "wine start /unix " + shlex.quote(str(lnk)), "exe")
            if len(load_apps()) > before:
                found.append(lnk.stem)
    return found


def user_processes():
    procs = {}
    for p in psutil.process_iter(["pid", "ppid", "uids", "name", "cmdline", "memory_info", "create_time"]):
        info = p.info
        if not info["uids"] or info["uids"].real != UID:
            continue
        cmdline = info["cmdline"] or []
        exe_name = Path(cmdline[0].replace("\\", "/")).name if cmdline else info["name"]
        if info["name"] in SESSION_PROCS or exe_name in SESSION_PROCS:
            continue
        if "/usr/lib/wine/" in (cmdline[0] if cmdline else "") and len(cmdline) == 1:
            continue
        procs[info["pid"]] = {
            "pid": info["pid"], "ppid": info["ppid"], "name": exe_name or info["name"],
            "mem": info["memory_info"].rss if info["memory_info"] else 0,
            "started": info["create_time"],
        }
    # Показываем только «корни», а дочерние процессы (вкладки Electron и т.п.) суммируем.
    roots = {pid: p for pid, p in procs.items() if p["ppid"] not in procs}
    for p in procs.values():
        root = p
        while root["ppid"] in procs:
            root = procs[root["ppid"]]
        if root is not p:
            roots[root["pid"]]["mem"] += p["mem"]
    return sorted(roots.values(), key=lambda p: p["started"])


def kill_tree(pid):
    try:
        parent = psutil.Process(pid)
    except psutil.NoSuchProcess:
        return
    if parent.uids().real != UID:
        abort(403)
    procs = parent.children(recursive=True) + [parent]
    for p in procs:
        try:
            p.terminate()
        except psutil.NoSuchProcess:
            pass
    _, alive = psutil.wait_procs(procs, timeout=3)
    for p in alive:
        try:
            p.kill()
        except psutil.NoSuchProcess:
            pass


def shared_path(rel):
    root = SHARED.resolve()
    p = (root / (rel or "").lstrip("/")).resolve()
    if p != root and root not in p.parents:
        abort(400)
    return p


# ---------------------------------------------------------------- auth

@app.before_request
def guard():
    if request.path in ("/login", "/auth/check", "/manifest.webmanifest") or request.path.startswith("/static/"):
        return None
    if session.get("ok"):
        return None
    if request.path.startswith("/api/"):
        return jsonify(error="Нужно войти"), 401
    return redirect("/login")


@app.get("/auth/check")
def auth_check():
    """Caddy спрашивает здесь, пускать ли к экрану (noVNC)."""
    return ("", 204) if session.get("ok") else redirect("/login")


@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "GET":
        return send_from_directory(STATIC, "login.html")
    ip = request.headers.get("X-Forwarded-For", request.remote_addr or "").split(",")[0].strip()
    now = time.time()
    fails = [t for t in LOGIN_FAILS.get(ip, []) if now - t < 300]
    if len(fails) >= 5:
        return jsonify(error="Слишком много попыток. Подожди 5 минут."), 429
    password = (request.get_json(silent=True) or {}).get("password", "")
    if PASSWORD and hmac.compare_digest(password.encode(), PASSWORD.encode()):
        LOGIN_FAILS.pop(ip, None)
        session.permanent = True
        session["ok"] = True
        return jsonify(ok=True)
    fails.append(now)
    LOGIN_FAILS[ip] = fails
    time.sleep(1)
    return jsonify(error="Неверный пароль"), 403


@app.post("/api/logout")
def logout():
    session.clear()
    return jsonify(ok=True)


# ---------------------------------------------------------------- pages

@app.get("/")
def index():
    return send_from_directory(STATIC, "index.html")


@app.get("/manifest.webmanifest")
def manifest():
    return send_from_directory(STATIC, "manifest.webmanifest", mimetype="application/manifest+json")


@app.get("/static/<path:name>")
def static_file(name):
    return send_from_directory(STATIC, name)


# ---------------------------------------------------------------- apps API

@app.get("/api/apps")
def api_apps():
    return jsonify(apps=load_apps())


@app.post("/api/apps/<app_id>/run")
def api_run(app_id):
    entry = next((a for a in load_apps() if a["id"] == app_id), None)
    if not entry:
        abort(404)
    try:
        pid = launch(entry["cmd"], entry["name"])
    except ValueError as e:
        return jsonify(error=str(e)), 400
    return jsonify(ok=True, pid=pid)


@app.put("/api/apps/<app_id>")
def api_edit(app_id):
    body = request.get_json(force=True)
    with apps_lock:
        apps = load_apps()
        for a in apps:
            if a["id"] == app_id:
                if body.get("name", "").strip():
                    a["name"] = body["name"].strip()
                if body.get("cmd", "").strip():
                    a["cmd"] = body["cmd"].strip()
                save_apps(apps)
                return jsonify(app=a)
    abort(404)


@app.delete("/api/apps/<app_id>")
def api_delete(app_id):
    with apps_lock:
        apps = load_apps()
        entry = next((a for a in apps if a["id"] == app_id), None)
        if not entry:
            abort(404)
        save_apps([a for a in apps if a["id"] != app_id])
    if request.args.get("files") == "1":
        if entry["type"] == "deb" and entry.get("package"):
            pkg = entry["package"]
            others = [a for a in load_apps() if a.get("package") == pkg]
            if not others:
                def remove(job):
                    with apt_lock:
                        job_run(job, ["apt-get", "remove", "-y", pkg])
                    for deb in DEBS.glob("*.deb"):
                        try:
                            if deb_package(deb) == pkg:
                                deb.unlink()
                        except (subprocess.CalledProcessError, OSError):
                            pass
                run_job(new_job(f"Удаление {pkg}"), remove)
        else:
            for token in shlex.split(entry["cmd"]):
                p = Path(token)
                if p.is_absolute() and p.is_file() and (PROGRAMS in p.parents or APPIMAGES in p.parents):
                    p.unlink()
                    if p.parent != PROGRAMS and p.parent.parent == PROGRAMS and not any(p.parent.iterdir()):
                        p.parent.rmdir()
    return jsonify(ok=True)


@app.get("/api/apps/<app_id>/log")
def api_app_log(app_id):
    entry = next((a for a in load_apps() if a["id"] == app_id), None)
    if not entry:
        abort(404)
    path = APP_LOGS / (clean_name(entry["name"]) + ".log")
    text = path.read_text(errors="replace")[-6000:] if path.exists() else "Лог пуст — программа ещё не запускалась."
    return jsonify(log=text)


@app.post("/api/apps/custom")
def api_custom():
    body = request.get_json(force=True)
    name, cmd = body.get("name", "").strip(), body.get("cmd", "").strip()
    if not name or not cmd:
        return jsonify(error="Нужны название и команда"), 400
    return jsonify(app=add_app(name, cmd, "cmd"))


@app.post("/api/upload")
def api_upload():
    results = []
    for f in request.files.getlist("file"):
        DOWNLOADS.mkdir(parents=True, exist_ok=True)
        tmp = unique_path(DOWNLOADS, clean_name(f.filename) + ".part")
        f.save(tmp)
        results.append(handle_file(tmp, f.filename))
    if not results:
        return jsonify(error="Файл не получен"), 400
    return jsonify(results=results)


@app.post("/api/install-url")
def api_install_url():
    url = (request.get_json(force=True).get("url") or "").strip()
    if not url.startswith(("http://", "https://")):
        return jsonify(error="Нужна ссылка, начинающаяся с http:// или https://"), 400
    job = run_job(new_job("Скачивание " + url.split("?")[0][-60:]), download_url, url)
    return jsonify(job=job["id"])


@app.post("/api/scan-wine")
def api_scan_wine():
    return jsonify(found=scan_wine_programs())


@app.post("/api/tool/<name>")
def api_tool(name):
    tools = {"terminal": "xterm", "explorer": "wine explorer", "winecfg": "winecfg",
             "taskmgr": "wine taskmgr"}
    if name not in tools:
        abort(404)
    launch(tools[name], name)
    return jsonify(ok=True)


@app.get("/api/jobs")
def api_jobs():
    jobs = sorted(JOBS.values(), key=lambda j: j["started"], reverse=True)
    return jsonify(jobs=[{**j, "log": j["log"][-40:]} for j in jobs])


# ---------------------------------------------------------------- processes & system

@app.get("/api/status")
def api_status():
    vm, sw = psutil.virtual_memory(), psutil.swap_memory()
    disk = shutil.disk_usage(DATA)
    try:
        size = subprocess.run(as_user(["xrandr", "--current"]), capture_output=True, text=True, timeout=5).stdout
        m = re.search(r"current (\d+) x (\d+)", size)
        screen = f"{m.group(1)}x{m.group(2)}" if m else None
    except (subprocess.SubprocessError, OSError):
        screen = None
    return jsonify(
        mem={"total": vm.total, "used": vm.total - vm.available},
        swap={"total": sw.total, "used": sw.used},
        disk={"total": disk.total, "used": disk.used},
        cpu=psutil.cpu_percent(interval=0.3), cores=psutil.cpu_count(),
        screen=screen, resolutions=RESOLUTIONS,
        procs=user_processes(),
    )


@app.post("/api/procs/<int:pid>/kill")
def api_kill(pid):
    kill_tree(pid)
    return jsonify(ok=True)


@app.post("/api/procs/kill-all")
def api_kill_all():
    subprocess.run(as_user(["wineserver", "-k"]), capture_output=True, timeout=15)
    for p in user_processes():
        kill_tree(p["pid"])
    return jsonify(ok=True)


@app.post("/api/screen")
def api_screen():
    size = request.get_json(force=True).get("size", "")
    if size not in RESOLUTIONS:
        return jsonify(error="Неизвестное разрешение"), 400
    w, h = size.split("x")
    modes = subprocess.run(as_user(["xrandr"]), capture_output=True, text=True, timeout=10).stdout
    if not re.search(rf"^\s+{size}\s", modes, re.M):
        # Xvnc умеет любые размеры, но режим сначала нужно добавить.
        subprocess.run(as_user(["xrandr", "--newmode", size, "60", w, w, w, w, h, h, h, h]),
                       capture_output=True, timeout=10)
        subprocess.run(as_user(["xrandr", "--addmode", "VNC-0", size]), capture_output=True, timeout=10)
    r = subprocess.run(as_user(["xrandr", "--output", "VNC-0", "--mode", size]),
                       capture_output=True, text=True, timeout=10)
    if r.returncode != 0:
        return jsonify(error=r.stderr.strip() or "xrandr не смог сменить разрешение"), 500
    return jsonify(ok=True)


# ---------------------------------------------------------------- shared files (телефон ↔ сервер)

@app.get("/api/files")
def api_files():
    folder = shared_path(request.args.get("path", ""))
    if not folder.is_dir():
        abort(404)
    items = []
    for p in folder.iterdir():
        try:
            st = p.stat()
        except OSError:
            continue
        items.append({"name": p.name, "dir": p.is_dir(), "size": st.st_size, "mtime": int(st.st_mtime)})
    items.sort(key=lambda i: (not i["dir"], i["name"].lower()))
    rel = folder.relative_to(SHARED.resolve())
    return jsonify(path="" if rel == Path(".") else rel.as_posix(), items=items)


@app.post("/api/files/upload")
def api_files_upload():
    folder = shared_path(request.args.get("path", ""))
    if not folder.is_dir():
        abort(404)
    saved = []
    for f in request.files.getlist("file"):
        dest = unique_path(folder, clean_name(f.filename))
        f.save(dest)
        chown(dest)
        saved.append(dest.name)
    return jsonify(saved=saved)


@app.post("/api/files/mkdir")
def api_files_mkdir():
    body = request.get_json(force=True)
    folder = shared_path(body.get("path", ""))
    dest = shared_path(str(folder.relative_to(SHARED.resolve()) / clean_name(body.get("name"))))
    dest.mkdir(exist_ok=True)
    chown(dest)
    return jsonify(ok=True)


@app.post("/api/files/delete")
def api_files_delete():
    p = shared_path(request.get_json(force=True).get("path", ""))
    if p == SHARED.resolve():
        abort(400)
    if p.is_dir() and not p.is_symlink():
        shutil.rmtree(p)
    elif p.exists() or p.is_symlink():
        p.unlink()
    return jsonify(ok=True)


@app.post("/api/files/install")
def api_files_install():
    """Установить программу, которая уже лежит в общей папке."""
    p = shared_path(request.get_json(force=True).get("path", ""))
    if not p.is_file():
        abort(404)
    tmp = unique_path(DOWNLOADS, p.name + ".part")
    shutil.copy2(p, tmp)
    return jsonify(result=handle_file(tmp, p.name))


@app.get("/download")
def download():
    p = shared_path(request.args.get("path", ""))
    if not p.is_file():
        abort(404)
    return send_file(p, as_attachment=True, download_name=p.name)


# ---------------------------------------------------------------- main

def main():
    for d in (SHARED, PROGRAMS, DEBS, DOWNLOADS, APP_LOGS):
        d.mkdir(parents=True, exist_ok=True)
    for leftover in DOWNLOADS.glob("*.part"):
        leftover.unlink(missing_ok=True)
    if not PASSWORD:
        print("ВНИМАНИЕ: KAIP_PASSWORD не задан — войти будет невозможно.", flush=True)
    if any(DEBS.glob("*.deb")):
        run_job(new_job("Проверка установленных deb-пакетов"), reinstall_debs)
    from waitress import serve
    serve(app, host="0.0.0.0", port=8000, threads=8, max_request_body_size=8 << 30,
          trusted_proxy="*", trusted_proxy_headers="x-forwarded-for x-forwarded-proto",
          clear_untrusted_proxy_headers=True)


if __name__ == "__main__":
    main()
