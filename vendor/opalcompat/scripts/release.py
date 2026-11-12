#!/usr/bin/env python3
"""Checks the built jar and copies it to release/: scripts/release.py "what it was tested with"

Run after ./gradlew build, on a committed tree: the name carries the commit. Older files in release/ stay. Fails without copying
anything when the jar's metadata is wrong, a mixin class is missing, a class the game loads without VulkanMod Reforged names it,
or a class differs from the jar last installed in test-instance/full, the one the tests ran on.
"""
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

# What runs with no VulkanMod in the game: the entry point and the part of the compat package OpenGL uses.
NO_VULKAN = ["dev/opalcompat/OpalCompat.class", "dev/opalcompat/compat/Compat.class", "dev/opalcompat/compat/Mods.class",
             "dev/opalcompat/compat/CustomImageUnits.class", "dev/opalcompat/compat/mixin/CompatMixinPlugin.class",
             "dev/opalcompat/compat/mixin/OpalImagesMixin.class", "dev/opalcompat/compat/mixin/OpalImageMixin.class",
             "dev/opalcompat/compat/mixin/FlywheelShaderPackMixin.class", "dev/opalcompat/compat/mixin/EveryCompatModulesMixin.class",
             "dev/opalcompat/compat/mixin/DhChunkyMixin.class", "dev/opalcompat/mixin/OpalCompatMixinPlugin.class"]


def newest(directory, pattern):
    return sorted(directory.glob(pattern), key=lambda path: path.stat().st_mtime)[-1]


def properties():
    values = {}
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def check(jar, values):
    problems = []
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        toml = archive.read("META-INF/neoforge.mods.toml").decode()
        for text in (f'modId="{values["mod_id"]}"', f'version="{values["mod_version"]}"', f'license="{values["mod_license"]}"',
                     f'versionRange="[{values["vulkanmod_version"]}]"'):
            if text not in toml:
                problems.append(f"no {text}")
        problems += [f"unexpanded {found}" for found in re.findall(r"\$\{[^}]+\}", toml)]
        for config_name in ("opalcompat.mixins.json", "opalcompat.compat.mixins.json"):
            if f'config="{config_name}"' not in toml:
                problems.append(f"{config_name} is not declared")
            config = json.loads(archive.read(config_name))
            package = config["package"].replace(".", "/")
            problems += [f"no mixin class {mixin}" for mixin in config["client"] if f"{package}/{mixin}.class" not in names]
        for name in ("LICENSE_opalcompat", "COPYING_opalcompat", "NOTICE_opalcompat", "NOTICE-vitrail_opalcompat", "assets/opalcompat/icon.png"):
            if name not in names:
                problems.append(f"no {name}")
        problems += [f"must not ship {name}" for name in names if name.startswith(("dev/opal/", "dev/vector/"))]
        for name in NO_VULKAN:
            if name not in names:
                problems.append(f"no {name}")
            elif b"net/vulkanmod" in archive.read(name):
                problems.append(f"{name} names VulkanMod and is loaded without it")
    return problems


def same_classes(jar, tested):
    """How many classes the two jars have, and those that differ."""
    with zipfile.ZipFile(jar) as a, zipfile.ZipFile(tested) as b:
        ours = {i.filename: i.CRC for i in a.infolist() if i.filename.endswith(".class")}
        theirs = {i.filename: i.CRC for i in b.infolist() if i.filename.endswith(".class")}
    different = sorted(name for name in set(ours) | set(theirs) if ours.get(name) != theirs.get(name))
    return len(ours), different


def main():
    tested_with = sys.argv[1] if len(sys.argv) > 1 else ""
    values = properties()
    commit = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"], text=True).strip()
    if subprocess.check_output(["git", "-C", str(ROOT), "status", "--porcelain"], text=True).strip():
        sys.exit("uncommitted changes: the name would lie about the commit")
    jar = ROOT / "build" / "libs" / f"{values['mod_id']}-{values['mod_version']}.jar"
    problems = check(jar, values)
    count, different = same_classes(jar, newest(ROOT / "test-instance" / "full" / "mods", "opalcompat-*.jar"))
    print(f"{jar.name}: {count} classes, {len(different)} differ from the jar the tests ran on")
    problems += [f"differs from what was tested: {name}" for name in different]
    if problems:
        sys.exit("\n".join(problems))

    release = ROOT / "release"
    release.mkdir(exist_ok=True)
    name = f"{values['mod_id']}-{values['mod_version']}-{commit}.jar"
    shutil.copy2(jar, release / name)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    print(name, digest)
    (release / "LATEST.txt").write_text(f"{name}\nsha256 {digest}\nbuilt from {commit}. {tested_with}\n")


if __name__ == "__main__":
    main()
