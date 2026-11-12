"""Which classes of a mod jar (and the jars inside it) call OpenGL directly, by the method references in their constant pools."""
import collections, io, struct, sys, zipfile

def refs(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        return [], []
    count = struct.unpack('>H', data[8:10])[0]
    pool = [None] * count
    at = 10
    i = 1
    while i < count:
        tag = data[at]
        if tag == 1:
            n = struct.unpack('>H', data[at + 1:at + 3])[0]
            pool[i] = ('utf', data[at + 3:at + 3 + n].decode('utf-8', 'replace'))
            at += 3 + n
        elif tag in (3, 4):
            at += 5
        elif tag in (5, 6):
            at += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = ('ref', tag, struct.unpack('>H', data[at + 1:at + 3])[0])
            at += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            a, b = struct.unpack('>HH', data[at + 1:at + 5])
            pool[i] = ('pair', tag, a, b)
            at += 5
        elif tag == 15:
            at += 4
        else:
            return [], []
        i += 1
    def utf(k):
        return pool[k][1]
    methods = []
    for e in pool:
        if e and e[0] == 'pair' and e[1] in (9, 10, 11):
            owner = utf(pool[e[2]][2])
            nat = pool[e[3]]
            methods.append((owner, utf(nat[2]), utf(nat[3])))
    strings = [utf(e[2]) for e in pool if e and e[0] == 'ref' and e[1] == 8]
    return methods, strings

GL = ('org/lwjgl/opengl/',)
GLSTATE = ('com/mojang/blaze3d/platform/GlStateManager',)
SHADER = ('net/neoforged/neoforge/client/event/RegisterShadersEvent',)

def scan(name, data, report):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for entry in z.namelist():
            if entry.endswith('.jar'):
                scan(name + '!' + entry, z.read(entry), report)
            elif entry.endswith('.class'):
                methods, strings = refs(z.read(entry))
                for owner, method, desc in methods:
                    if owner.startswith(GL):
                        report['gl'][name][entry].add(owner.split('/')[-1] + '.' + method)
                    elif owner.startswith(GLSTATE):
                        report['state'][name][entry].add(method)
                    elif owner.startswith(SHADER):
                        report['shaders'][name][entry].add(method)
                for s in strings:
                    if ('rendertype_' in s or s.startswith('shaders/')) and len(s) < 80:
                        report['names'][name][entry].add(s)

def tree():
    return collections.defaultdict(lambda: collections.defaultdict(set))

for path in sys.argv[1:]:
    report = {k: tree() for k in ('gl', 'state', 'shaders', 'names')}
    scan(path.replace('\\', '/').split('/')[-1], open(path, 'rb').read(), report)
    print('=====', path)
    for kind in ('gl', 'shaders', 'names'):
        for jar, classes in report[kind].items():
            print(f'--- {kind} in {jar}: {len(classes)} classes')
            for cls, items in sorted(classes.items())[:70]:
                print('   ', cls[:-6], sorted(items)[:14], ('+%d' % (len(items) - 14)) if len(items) > 14 else '')
    for jar, classes in report['state'].items():
        print(f'--- GlStateManager in {jar}: {len(classes)} classes:', [c[:-6].split('/')[-1] for c in sorted(classes)][:30])
