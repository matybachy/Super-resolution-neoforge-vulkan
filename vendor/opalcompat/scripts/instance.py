#!/usr/bin/env python3
"""A clean NeoForge client outside the dev run, for testing the built jar.

  scripts/instance.py create NAME          install NeoForge into test-instance/NAME
  scripts/instance.py install NAME         the freshly built jar, VulkanMod Reforged and the test packs into it
  scripts/instance.py run NAME [options]   start it with what is in its mods/ folder, then summarise the run

Options of run:
  --pack NAME      shader pack (opalcompat.pack)
  --options A=b,C=d
  --script FILE    an autotest script (see AutoTest); without one, the plain autotest
  --camera x,z,yaw,pitch,height
  --time N
  --timeout SECONDS
  --no-validation  without the Khronos layer
  --no-autotest    just start the game
  --jvm ARG        one more JVM argument, repeatable

Options of install:
  --renderer gl    without VulkanMod Reforged
  --extra DIR      every jar of test-instance/jars/DIR too, repeatable

Output goes to test-instance/NAME/autotest. The Java is Zulu 21 from JAVA_HOME or the usual place.
"""
import argparse
import collections
import json
import os
import pathlib
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
# The version the instance is created with; Opal and Vectors ask for 21.1.259 or newer.
NEOFORGE = "21.1.265"
MINECRAFT = "1.21.1"
JAVA_HOME = pathlib.Path(os.environ.get("VITRAILVK_JAVA", r"C:\Program Files\Zulu\zulu-21"))
JAVA = JAVA_HOME / "bin" / ("java.exe" if os.name == "nt" else "java")


def instance(name):
    return ROOT / "test-instance" / name


def create(name):
    home = instance(name)
    home.mkdir(parents=True, exist_ok=True)
    installer = ROOT / "test-instance" / f"neoforge-{NEOFORGE}-installer.jar"
    if not installer.exists():
        url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{NEOFORGE}/neoforge-{NEOFORGE}-installer.jar"
        print("downloading", url)
        installer.write_bytes(urllib.request.urlopen(url, timeout=300).read())
    profiles = home / "launcher_profiles.json"
    if not profiles.exists():
        profiles.write_text('{"profiles":{}}')
    subprocess.check_call([str(JAVA), "-jar", str(installer), "--install-client", str(home)])
    (home / "mods").mkdir(exist_ok=True)
    print("installed in", home)


def neoforge_of(home):
    """The NeoForge an instance was created with."""
    found = sorted(path.name for path in (home / "versions").glob("neoforge-*"))
    return found[-1][len("neoforge-"):] if found else NEOFORGE


def newest(directory, pattern):
    return sorted(directory.glob(pattern), key=lambda path: path.stat().st_mtime)[-1]


def install(name, renderer="vulkan", extra=()):
    """The freshly built jar. renderer "gl" leaves VulkanMod Reforged out of the instance."""
    home = instance(name)
    mods = home / "mods"
    mods.mkdir(exist_ok=True)
    for pattern in ("opalcompat-*.jar", "vitrailvk-*.jar", "vitrailbridge-*.jar", "VulkanMod-Reforged-*.jar"):
        for old in mods.glob(pattern):
            old.unlink()
    built = newest(ROOT / "build" / "libs", "opalcompat-*.jar")
    shutil.copy2(built, mods)
    print("installed", built.name)
    if renderer == "vulkan":
        for library in (ROOT / "libs").glob("VulkanMod-Reforged-*.jar"):
            shutil.copy2(library, mods)
    for directory in extra:
        for jar in (ROOT / "test-instance" / "jars" / directory).glob("*.jar"):
            shutil.copy2(jar, mods)
            print("installed", jar.name)
    packs = home / "shaderpacks"
    packs.mkdir(exist_ok=True)
    for pack in (ROOT / "run" / "shaderpacks").iterdir():
        if pack.is_dir():
            shutil.copytree(pack, packs / pack.name, dirs_exist_ok=True)
        else:
            shutil.copy2(pack, packs)
    print("installed", built.name)


# VulkanMod Reforged's own, without any pack: see PORTING.md.
BASELINE = {"VUID-vkQueueSubmit-pSignalSemaphores-00067", "VUID-vkDestroyDevice-device-05137"}


def summarise(home, started=0.0):
    out = home / "autotest"
    result = out / "result.txt"
    errors = sorted((home / "logs").glob("latest.log")) if (home / "logs").exists() else []
    for log in errors:
        loading = [line[:300] for line in log.read_text(errors="replace").splitlines() if "ModLoadingIssue" in line or "Missing or unsupported mandatory" in line or "requires " in line and "or above" in line]
        if loading:
            print("--- mod loading:")
            print("\n".join(loading[:12]))
    print("--- result")
    print(result.read_text() if result.exists() else "(no result.txt)")
    log = out / "validation.log"
    if log.exists():
        counts = collections.Counter(re.findall(r"VUID-[A-Za-z0-9_-]+|UNASSIGNED-[A-Za-z0-9_-]+|Undefined-Value-[A-Za-z0-9_-]+", log.read_text(errors="replace")))
        print("--- validation", {k: v for k, v in counts.most_common(14)}, "new:", sorted(set(counts) - BASELINE)[:14])
    latest = home / "logs" / "latest.log"
    if latest.exists():
        lines = latest.read_text(errors="replace").splitlines()
        picked = [line[:330] for line in lines if re.search(r"/(ERROR|FATAL)\]|Exception in|Caused by", line) and "Error loading" not in line]
        print("--- log errors:", len(picked))
        print("\n".join(picked[:30]))
    crash = sorted((home / "crash-reports").glob("*.txt"), key=lambda path: path.stat().st_mtime)[-1:] if (home / "crash-reports").exists() else []
    if crash and crash[0].stat().st_mtime > started:
        print("--- CRASH", crash[0])
        print("\n".join(crash[0].read_text(errors="replace").splitlines()[:60]))


def library_path(home, entry):
    if "downloads" in entry and entry["downloads"].get("artifact"):
        return home / "libraries" / entry["downloads"]["artifact"]["path"]
    group, artifact, version, *rest = entry["name"].split(":")
    classifier = f"-{rest[0]}" if rest else ""
    return home / "libraries" / group.replace(".", "/") / artifact / version / f"{artifact}-{version}{classifier}.jar"


def allowed(entry):
    for rule in entry.get("rules", []):
        os_rule = rule.get("os", {})
        matches = not os_rule or os_rule.get("name") == {"Windows": "windows", "Linux": "linux", "Darwin": "osx"}[platform.system()]
        if rule["action"] == "allow" and not matches:
            return False
        if rule["action"] == "disallow" and matches:
            return False
    return True


def fetch_missing(home, version):
    for entry in version["libraries"]:
        if not allowed(entry):
            continue
        path = library_path(home, entry)
        artifact = entry.get("downloads", {}).get("artifact")
        if not path.exists() and artifact and artifact.get("url"):
            path.parent.mkdir(parents=True, exist_ok=True)
            print("downloading", artifact["url"])
            path.write_bytes(urllib.request.urlopen(artifact["url"], timeout=300).read())


def fetch_assets(home, vanilla):
    index = vanilla["assetIndex"]
    index_file = home / "assets" / "indexes" / f"{index['id']}.json"
    if not index_file.exists():
        index_file.parent.mkdir(parents=True, exist_ok=True)
        index_file.write_bytes(urllib.request.urlopen(index["url"], timeout=300).read())
    objects = json.loads(index_file.read_text())["objects"]
    missing = []
    for value in objects.values():
        hash_ = value["hash"]
        path = home / "assets" / "objects" / hash_[:2] / hash_
        if not path.exists():
            missing.append((hash_, path))
    if not missing:
        return index["id"]
    print(len(missing), "assets to fetch")
    from concurrent.futures import ThreadPoolExecutor

    def one(item):
        hash_, path = item
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(urllib.request.urlopen(f"https://resources.download.minecraft.net/{hash_[:2]}/{hash_}", timeout=300).read())

    with ThreadPoolExecutor(16) as pool:
        list(pool.map(one, missing))
    return index["id"]


def run(name, args):
    home = instance(name)
    neoforge_version = neoforge_of(home)
    neoforge = json.loads((home / "versions" / f"neoforge-{neoforge_version}" / f"neoforge-{neoforge_version}.json").read_text())
    vanilla_file = home / "versions" / MINECRAFT / f"{MINECRAFT}.json"
    vanilla = json.loads(vanilla_file.read_text())
    fetch_missing(home, neoforge)
    fetch_missing(home, vanilla)
    client = home / "versions" / MINECRAFT / f"{MINECRAFT}.jar"
    if not client.exists():
        client.write_bytes(urllib.request.urlopen(vanilla["downloads"]["client"]["url"], timeout=300).read())
    asset_index = fetch_assets(home, vanilla)

    separator = os.pathsep
    seen = {}
    for version in (vanilla, neoforge):
        for entry in version["libraries"]:
            if allowed(entry):
                # A later entry replaces the same library of another version, as the launcher does.
                seen[":".join(entry["name"].split(":")[:2] + entry["name"].split(":")[3:])] = library_path(home, entry)
    # Under the version's own name, which is what ignoreList keeps out of the module layer: the patched game comes from libraries.
    named = home / "versions" / f"neoforge-{neoforge_version}" / f"neoforge-{neoforge_version}.jar"
    if not named.exists():
        shutil.copy2(client, named)
    classpath = list(seen.values()) + [named]
    substitutions = {
        "${library_directory}": str(home / "libraries"),
        "${classpath_separator}": separator,
        "${classpath}": separator.join(str(p) for p in classpath),
        "${version_name}": f"neoforge-{neoforge_version}",
        "${natives_directory}": str(home / "natives"),
        "${launcher_name}": "opalcompat-test",
        "${launcher_version}": "1",
        "${auth_player_name}": "Tester",
        "${game_directory}": str(home),
        "${assets_root}": str(home / "assets"),
        "${assets_index_name}": asset_index,
        "${auth_uuid}": "00000000000000000000000000000000",
        "${auth_access_token}": "0",
        "${clientid}": "0",
        "${auth_xuid}": "0",
        "${user_type}": "legacy",
        "${version_type}": "release",
    }

    def fill(items):
        result = []
        for item in items:
            if isinstance(item, dict):
                if not allowed(item) or item.get("rules") and any("features" in rule for rule in item["rules"]):
                    continue
                value = item["value"]
                values = value if isinstance(value, list) else [value]
            else:
                values = [item]
            for value in values:
                for key, replacement in substitutions.items():
                    value = value.replace(key, replacement)
                result.append(value)
        return result

    (home / "natives").mkdir(exist_ok=True)
    out = home / "autotest"
    out.mkdir(exist_ok=True)
    jvm = ["-Xmx6G", "-XX:+UseG1GC"]
    env = dict(os.environ)
    if not args.no_autotest:
        jvm += ["-Dopalcompat.autotest=true", f"-Dopalcompat.autotest.out={out}"]
        jvm.append(f"-Dopalcompat.autotest.camera={args.camera}")
        jvm.append(f"-Dopalcompat.autotest.time={args.time}")
        jvm.append(f"-Dopalcompat.autotest.timeout={args.timeout}")
        if args.script:
            jvm.append(f"-Dopalcompat.autotest.script={pathlib.Path(args.script).resolve()}")
    if args.pack is not None:
        jvm.append(f"-Dopalcompat.pack={args.pack}")
    if args.options:
        jvm.append(f"-Dopalcompat.options={args.options}")
    jvm += args.jvm or []
    validation = ROOT / "tools" / "vvl"
    log = out / "validation.log"
    if log.exists():
        log.unlink()
    if not args.no_validation and (validation / "VkLayer_khronos_validation.json").exists():
        env.update({
            "VK_ADD_LAYER_PATH": str(validation),
            "VK_INSTANCE_LAYERS": "VK_LAYER_KHRONOS_validation",
            "VK_KHRONOS_VALIDATION_DEBUG_ACTION": "VK_DBG_LAYER_ACTION_LOG_MSG",
            "VK_KHRONOS_VALIDATION_REPORT_FLAGS": "error,warn",
            "VK_KHRONOS_VALIDATION_LOG_FILENAME": str(log),
        })
    command = [str(JAVA)] + jvm + fill(vanilla["arguments"]["jvm"]) + fill(neoforge["arguments"]["jvm"])
    command += [neoforge["mainClass"]] + fill(vanilla["arguments"]["game"]) + fill(neoforge["arguments"]["game"])
    # The launcher hands the vanilla arguments that take a value in pairs; a feature flag lost its value with fill().
    (out / "command.txt").write_text("\n".join(command))
    console = open(out / "console.log", "w", encoding="utf-8")
    started = time.time()
    process = subprocess.Popen(command, cwd=home, env=env, stdout=console, stderr=subprocess.STDOUT)
    # The autotest's watchdog only starts with the mods; a game stuck on the mod loading error screen never gets that far.
    try:
        code = process.wait(int(args.timeout) + 120)
    except subprocess.TimeoutExpired:
        process.kill()
        code = process.wait()
        print("killed: still running after the timeout")
    print("exit code", code)
    summarise(home, started)
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=["create", "install", "run"])
    parser.add_argument("name")
    parser.add_argument("--pack")
    parser.add_argument("--options", default="")
    parser.add_argument("--script")
    parser.add_argument("--camera", default="0,0,135,20,14")
    parser.add_argument("--time", default="6000")
    parser.add_argument("--timeout", default="600")
    parser.add_argument("--no-validation", action="store_true")
    parser.add_argument("--no-autotest", action="store_true")
    parser.add_argument("--jvm", action="append")
    parser.add_argument("--renderer", choices=["vulkan", "gl"], default="vulkan", help="install: without VulkanMod Reforged")
    parser.add_argument("--extra", action="append", default=[], help="install: every jar of test-instance/jars/EXTRA too")
    args = parser.parse_args()
    if args.action == "create":
        create(args.name)
    elif args.action == "install":
        install(args.name, args.renderer, args.extra)
    else:
        sys.exit(run(args.name, args))


if __name__ == "__main__":
    main()
