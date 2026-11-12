"""Copies the version-independent part of Vitrail into src/main/java, with origin headers and the
adaptations 1.21.1 needs. Usage: python -I scripts/take_from_vitrail.py   (from the project root)

Group "a" of ANALYSIS.md: files that import nothing of the game, of Sodium, of Mixin or of the Vulkan
bindings, directly or through another Vitrail file. A few "boundary" files are added by name.
Files OpalCompat replaces (written by hand, see PORTING.md) are never overwritten.
"""
import os
import re

SRC = 'refs/Vitrail-Shaders/common/src/main/java'
DST = 'src/main/java'
COMMIT = '7a3cdcb'
GAME = re.compile(r'^(com\.mojang|net\.minecraft|net\.caffeinemc|org\.spongepowered|com\.llamalad7|'
                  r'org\.lwjgl\.vulkan|org\.lwjgl\.util\.vma|net\.neoforged|net\.fabricmc|com\.seibel)')
BOUNDARY = ['PackValues', 'ViewMatrices', 'CapturedProjection', 'ShadowAmortisation', 'ShadowCullPlan',
            'BlockStateIds', 'PackNameIds', 'BiomeClassifier']
OWN = {'dev/vitrail/glsl/SodiumVertex.java', 'dev/vitrail/glsl/GameTransforms.java',
       'dev/vitrail/render/FrameState.java', 'dev/vitrail/render/FaceShading.java',
       'dev/vitrail/render/HandDraw.java', 'dev/vitrail/render/ShadowTargets.java',
       'dev/vitrail/render/PackImages.java'}
MARK = '// Unchanged unless marked with a "OpalCompat:" comment.\n'


def classify():
    files = {}
    for dp, _dirs, fns in os.walk(SRC):
        for fn in fns:
            if fn.endswith('.java'):
                path = os.path.join(dp, fn)
                rel = os.path.relpath(path, SRC).replace(os.sep, '/')
                source = open(path, encoding='utf-8').read()
                cls = rel[:-5].replace('/', '.')
                imports = re.findall(r'^import (?:static )?([\w.]+);', source, re.M)
                files[cls] = dict(rel=rel, pkg=cls.rsplit('.', 1)[0], src=source, imports=imports,
                                  game=any(GAME.match(i) for i in imports))
    deps = {}
    for cls, info in files.items():
        found = set()
        for imported in info['imports']:
            parts = imported.split('.')
            for k in range(len(parts), 0, -1):
                candidate = '.'.join(parts[:k])
                if candidate in files:
                    found.add(candidate)
                    break
        for other, other_info in files.items():
            if other != cls and other_info['pkg'] == info['pkg'] \
                    and re.search(r'\b' + other.rsplit('.', 1)[1] + r'\b', info['src']):
                found.add(other)
        deps[cls] = found
    tainted = {c for c, i in files.items() if i['game']}
    changed = True
    while changed:
        changed = False
        for cls in files:
            if cls not in tainted and deps[cls] & tainted:
                tainted.add(cls)
                changed = True
    return files, tainted


def is_ident(ch):
    return ch.isalnum() or ch in '_$'


def rename_unnamed(s):
    """Java 21 has no unnamed variables: _ becomes unusedN, outside comments and literals."""
    out = []
    i = 0
    count = 0
    n = len(s)
    while i < n:
        c = s[i]
        if s.startswith('//', i):
            j = s.find('\n', i)
            j = n if j < 0 else j
            out.append(s[i:j])
            i = j
        elif s.startswith('/*', i):
            j = s.find('*/', i + 2) + 2
            out.append(s[i:j])
            i = j
        elif s.startswith('"""', i):
            j = s.find('"""', i + 3) + 3
            out.append(s[i:j])
            i = j
        elif c == '"' or c == "'":
            j = i + 1
            while s[j] != c:
                j += 2 if s[j] == '\\' else 1
            out.append(s[i:j + 1])
            i = j + 1
        elif c == '_' and (i == 0 or not is_ident(s[i - 1])) and (i + 1 >= n or not is_ident(s[i + 1])):
            count += 1
            out.append('unused%d' % count)
            i += 1
        else:
            out.append(c)
            i += 1
    return ''.join(out), count


def adapt(rel, s, notes):
    name = rel.rsplit('/', 1)[1][:-5]
    if name in ('BlockStateIds', 'PackNameIds', 'BiomeClassifier'):
        s = s.replace('net.minecraft.resources.Identifier;', 'net.minecraft.resources.ResourceLocation;')
        s = re.sub(r'\bIdentifier\b', 'ResourceLocation', s)
        notes.append('net.minecraft.resources.Identifier (26.x) is ResourceLocation in 1.21.1.')
    if name == 'BiomeClassifier':
        s = s.replace('key.identifier()', 'key.location()').replace('k -> k.identifier()', 'k -> k.location()')
        notes.append('ResourceKey.identifier() (26.x) is location() in 1.21.1.')
    if name == 'BlockStateIds':
        s = s.replace('minecraft.levelExtractor.allChanged();', 'minecraft.levelRenderer.allChanged();')
        s = s.replace('BuiltInRegistries.BLOCK.get(identifier).map(Holder::value).orElse(null);',
                      'BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);')
        s = s.replace('BuiltInRegistries.BLOCK.getTags()\n',
                      'BuiltInRegistries.BLOCK.getTags()\n\t\t\t\t.map(com.mojang.datafixers.util.Pair::getSecond)\n')
        notes.append('levelExtractor and the registry calls follow the 1.21.1 API.')
    return s


def main():
    files, tainted = classify()
    wanted = [c for c in files if c not in tainted]
    wanted += ['dev.vitrail.render.' + b for b in BOUNDARY]
    copied = 0
    for cls in wanted:
        info = files[cls]
        rel = info['rel']
        if rel in OWN:
            continue
        notes = []
        text = adapt(rel, info['src'], notes)
        text, count = rename_unnamed(text)
        if count:
            notes.append('unnamed variables (_) renamed to unusedN, Java 21 has no unnamed variables.')
        header = ('// Taken from Vitrail (https://github.com/avpbynf/Vitrail-Shaders), commit %s,\n'
                  '// common/src/main/java/%s. LGPL-3.0, see NOTICE and NOTICE-vitrail.\n' % (COMMIT, rel)) + MARK
        for note in notes:
            header += '// OpalCompat: ' + note + '\n'
        target = os.path.join(DST, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'w', encoding='utf-8', newline='') as out:
            out.write(header + text)
        copied += 1
    print('copied', copied, 'files; group a:', len(files) - len(tainted))


main()
