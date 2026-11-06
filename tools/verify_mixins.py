#!/usr/bin/env python3
"""跨版本 mixin 注入目标自动校验。

对 dist/ 里的每个 fouryctrl jar：
  1. 解析所有 mixin 类的 @Mixin 目标类与 @Inject/@Accessor 方法名（字节码注解）
  2. 在对应 MC 版本的 merged jar 里验证：类存在、方法存在（按名字；yarn 版为 intermediary 名）
  3. 输出逐版本 PASS/FAIL 表

用法: python3 tools/verify_mixins.py [--jar dist/fouryctrl-1.0.0+X.jar ...]
"""
import subprocess, zipfile, re, os, sys, tempfile, struct, io

LOOM = os.path.expanduser('~/.gradle/caches/fabric-loom')

# ---------- tiny v2 映射（yarn 版需要 intermediary -> official/obf 名） ----------
def load_tiny(path):
    """返回 (cmap, mmap): intermediary class -> official class; (official class, intermediary method) -> official method。
    tiny v2 行格式（首列可能为空因前导\t）: 'c\toff\tinter' / '\tm\tdesc\toffname\tintername'"""
    cmap, mmap, fmap = {}, {}, {}
    cur_off = None
    with open(path) as f:
        f.readline()
        for line in f:
            p = line.rstrip('\n').split('\t')
            if p[0] == 'c':
                cur_off = p[1]
                cmap[p[2]] = cur_off
            elif p[0] == '' and len(p) > 4 and p[1] == 'm':
                # 同一 intermediary 方法名可能由多个 obf 类声明（继承覆盖），收集候选集合
                mmap.setdefault((cur_off, p[4]), set()).add(p[3])
            elif p[0] == '' and len(p) > 4 and p[1] == 'f':
                # 字段行同方法行格式；@Accessor("field_xxx") 需要映射后校验字段真的存在
                fmap.setdefault((cur_off, p[4]), set()).add(p[3])
    return cmap, mmap, fmap

# ---------- 最小 class 解析器：常量池 + RuntimeInvisibleAnnotations ----------
def parse_class(data):
    cp, i = {}, 10  # skip magic/version
    n = struct.unpack_from('>H', data, 8)[0]
    idx = 1
    while idx < n:
        tag = data[i]; i += 1
        if tag == 1:  # Utf8
            ln = struct.unpack_from('>H', data, i)[0]; i += 2
            cp[idx] = ('u', data[i:i+ln].decode('utf-8', 'replace')); i += ln
        elif tag in (7, 8, 16, 19, 20):  # Class/Str/MethodType/Module/Pkg: u2
            cp[idx] = ('x', struct.unpack_from('>H', data, i)[0]); i += 2
        elif tag == 15:  # MethodHandle spec? actually 15=MethodHandle is u3
            cp[idx] = ('x', None); i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # int/float/field/method/iface/name-type/dynamic/invoke
            cp[idx] = ('x', struct.unpack_from('>H', data, i)[0] if tag != 3 and tag != 4 else struct.unpack_from('>i', data, i)[0]); i += 4 if tag in (3,4) else 4
        elif tag == 5 or tag == 6:  # long/double take 2 slots
            i += 8; idx += 1
        else:
            raise ValueError(f'tag {tag}')
        idx += 1
    # flags, this, super, ifaces
    i += 2 + 2 + 2
    ni = struct.unpack_from('>H', data, i-2)[0]
    i += ni * 2
    # fields
    nf = struct.unpack_from('>H', data, i)[0]; i += 2
    for _ in range(nf):
        i += 6
        na = struct.unpack_from('>H', data, i)[0]; i += 2
        for _ in range(na):
            i += 2
            ln = struct.unpack_from('>I', data, i)[0]; i += 4 + ln
    # methods
    nm = struct.unpack_from('>H', data, i)[0]; i += 2
    methods = []  # (name, [(ann_type, {method:[names], target:[...]})])
    for _ in range(nm):
        name_idx, desc_idx = struct.unpack_from('>HH', data, i)[0:2]
        i += 4
        nm_name = cp[cp[name_idx][1]][1] if False else None
        na = struct.unpack_from('>H', data, i)[0]; i += 2
        anns = []
        for _ in range(na):
            i += 2  # attribute name
            ln = struct.unpack_from('>I', data, i)[0]; i += 4
            body = data[i:i+ln]; i += ln
            if b'Mixin' in body or b'Inject' in body or b'Accessor' in body or b'Invoker' in body:
                anns.append(body)
        methods.append((cp[name_idx][1], anns))
    return cp, methods

def utf8s(body):
    """annotation body 里所有可打印 Utf8 引用无法直接拿, 改为原始字节里提取可读串"""
    return re.findall(rb'[\x20-\x7e]{4,}', body)

# ---------- 简化：直接在 javap -v 输出上正则（慢一点但可靠） ----------
def javap(jar, cls):
    with tempfile.TemporaryDirectory() as td:
        with zipfile.ZipFile(jar) as z:
            open(td+'/C.class','wb').write(z.read(cls))
        out = subprocess.run(['javap','-p','-v',td+'/C.class'],capture_output=True,text=True).stdout
    return out

def extract_annotations(out):
    """返回 (mixin_targets:[L...;], inject_method_names:[...])。
    javap -v 注解展开格式：
      org.spongepowered.asm.mixin.Mixin(\n value=[class Lnet/minecraft/...;]\n )
      org.spongepowered.asm.mixin.injection.Inject(\n method=["method_1753"]\n at=[@...]\n )
    """
    targets = []
    m = re.search(r'org\.spongepowered\.asm\.mixin\.Mixin\(\s*value=\[(.*?)\]\s*\)', out, re.S)
    if m:
        targets = re.findall(r'class (L[\w/$;]+)', m.group(1))
    # @Inject method=["a","b"] 或 method=["a"] —— 简化：直接在 Inject( 块内找所有 "..."
    injects = []
    for m in re.finditer(r'org\.spongepowered\.asm\.mixin\.injection\.Inject\(\s*method=\[(.*?)\]', out, re.S):
        injects += re.findall(r'"([^"]+)"', m.group(1))
    # Accessor/Invoker: 显式 value 的目标成员名（不带 value 的默认用方法名，这里不处理）
    accessors = re.findall(r'org\.spongepowered\.asm\.mixin\.gen\.Accessor\(\s*value="([^"]+)"', out)
    invokers = re.findall(r'org\.spongepowered\.asm\.mixin\.gen\.Invoker\(\s*value="([^"]+)"', out)
    return targets, injects, accessors, invokers

def mc_methods(mcjar, internal_name):
    """列出 MC jar 中某类的所有方法名。internal_name 形如 net/minecraft/class_329"""
    with zipfile.ZipFile(mcjar) as z:
        try:
            data = z.read(internal_name + '.class')
        except KeyError:
            return None  # class missing
    with tempfile.TemporaryDirectory() as td:
        open(td+'/C.class','wb').write(data)
        out = subprocess.run(['javap','-p',td+'/C.class'],capture_output=True,text=True).stdout
    names = set()
    for l in out.splitlines():
        m = re.match(r'\s*(?:public|protected|private|static|final|\s)*[\w<>\[\].$, ]+\s+(\w+)\(', l)
        if m: names.add(m.group(1))
    return names

_jar_cache = {}
def members_and_super(mcjar, obf):
    """返回 (方法名集合, 字段名集合, super类名)。类缺失返回 (None, None, None)。"""
    key = (mcjar, obf)
    if key in _jar_cache: return _jar_cache[key]
    try:
        with zipfile.ZipFile(mcjar) as z:
            data = z.read(obf + '.class')
    except KeyError:
        _jar_cache[key] = (None, None, None)
        return None, None, None
    with tempfile.TemporaryDirectory() as td:
        open(td+'/C.class','wb').write(data)
        out = subprocess.run(['javap','-p',td+'/C.class'],capture_output=True,text=True).stdout
    ms = set(re.findall(r'\s[\w<>\[\].$, ]+\s+(\w+)\(.*\);', out))
    # 字段行形如 '  protected net.minecraft.util.math.Vec2f movementVector;'（无括号）
    fs = set(re.findall(r'^\s+(?:(?:public|protected|private|static|final|volatile|transient)\s+)+[\w<>\[\].$, ]+\s+(\w+);$', out, re.M))
    sup = re.search(r' extends ([\w/$]+)(?:<| |\{|$)', out)
    r = (ms, fs, sup.group(1) if sup else None)
    _jar_cache[key] = r
    return r

def methods_and_super(mcjar, obf):
    """兼容旧调用：只要 (方法名集合, super)。"""
    ms, _fs, sup = members_and_super(mcjar, obf)
    return ms, sup

def strip_L(s):
    return s[1:-1] if s.startswith('L') and s.endswith(';') else s

def verify(jar, mcjar, label, tiny=None):
    print(f'=== {label} ===')
    ok = True
    cmap, mmap, fmap = load_tiny(tiny) if tiny else ({}, {}, {})
    with zipfile.ZipFile(jar) as z:
        mixins = [n for n in z.namelist() if n.startswith('com/fouryctrl/mixin/') and n.endswith('.class') and '$' not in n]
    for cls in sorted(mixins):
        out = javap(jar, cls)
        targets, injects, accessors, invokers = extract_annotations(out)
        short = os.path.basename(cls)
        if not targets:
            print(f'  {short}: 无 @Mixin 目标?')
            continue
        for t in targets:
            internal = strip_L(t)
            if cmap and internal.startswith('net/minecraft/class_'):
                # yarn 版：intermediary -> obf 候选名，沿 super 链验证
                obf = cmap.get(internal)
                if obf is None:
                    print(f'  ✗ {short}: {internal} 不在 tiny 映射中!')
                    ok = False
                    continue
                if not injects:
                    # 无 @Inject：Accessor 验字段、Invoker 验方法（沿 super 链，mixin 的
                    # @Accessor 本身也只查目标类，但字段可能声明在父类 —— 那种写法才是坑，
                    # 所以这里严格按「目标类自身」校验，与 Mixin 运行期行为一致）
                    cur, msel = obf, []
                    for f in accessors:
                        cands = fmap.get((cur, f))
                        if cands:
                            msel.append(f'field {f}->{sorted(cands)}')
                        else:
                            msel.append(f'✗field {f} 不在 {internal}(obf {obf})')
                            ok = False
                    for m in invokers:
                        cands = mmap.get((cur, m))
                        if cands:
                            msel.append(f'invoke {m}->{sorted(cands)}')
                        else:
                            msel.append(f'✗invoke {m} 不在 {internal}(obf {obf})')
                            ok = False
                    tag = ' '.join(msel) if msel else '[accessor]'
                    if any(s.startswith('✗') for s in msel):
                        print(f'  ✗ {short} → {internal} (obf {obf}) {tag}')
                    else:
                        print(f'  ✓ {short} → {internal} (obf {obf}) {tag}')
                    continue
                cands = set()
                for (c, n), ms in mmap.items():
                    if n in injects: cands |= ms
                cur, found = obf, []
                while cur and not cur.startswith(('net/minecraft','com/mojang','java','javax','com/google','org')):
                    ms, _fs, sup = members_and_super(mcjar, cur)
                    if ms is None:
                        print(f'  ✗ {short}: obf 类 {cur} 不存在于 MC jar!')
                        ok = False
                        found = None
                        break
                    hit = cands & ms
                    if hit: found.append(f'{cur}:{sorted(hit)}')
                    cur = sup
                if found:
                    print(f'  ✓ {short} → {internal} (obf {obf}) 注入{sorted(set(injects))}')
                elif found is not None or True:
                    if not found:
                        print(f'  ✗ {short}: 注入{injects} 在 {internal} (obf {obf}) 及父链均未找到!')
                        ok = False
                ok = ok and bool(found)
            else:
                # 官方名版本（26.x）或非 MC 类（RenderSystem 等）直接查
                mnames, fnames, _ = members_and_super(mcjar, internal)
                if mnames is None:
                    print(f'  ✗ {short}: 目标类 {internal} 在 MC 中不存在!')
                    ok = False
                    continue
                bad = [n for n in injects if n not in mnames and not n.startswith('*')]
                badf = [f for f in accessors if f not in fnames]
                badi = [m for m in invokers if m not in mnames]
                if not (bad or badf or badi):
                    ij = f' 注入{sorted(set(injects))}' if injects else ''
                    acc = f' @Accessor{sorted(accessors)}' if accessors else ''
                    inv = f' @Invoker{sorted(invokers)}' if invokers else ''
                    print(f'  ✓ {short} → {internal}{ij}{acc}{inv}')
                else:
                    if bad:  print(f'  ✗ {short}: 方法不存在于 {internal}: {bad}')
                    if badf: print(f'  ✗ {short}: @Accessor 字段不存在于 {internal}: {badf}')
                    if badi: print(f'  ✗ {short}: @Invoker 方法不存在于 {internal}: {badi}')
                    ok = False
    return ok

def main():
    versions = ['1.20.1','1.20.2','1.20.4','1.20.6','1.21.1','1.21.4','1.21.5','1.21.8','1.21.11','26.1','26.2','26.3']
    all_ok = True
    for v in versions:
        jar = f'dist/fouryctrl-1.0.0+{v}.jar'
        mcjar = os.path.join(LOOM, v, 'minecraft-merged.jar')
        if not os.path.exists(jar): print(f'=== {v}: jar 缺失'); all_ok=False; continue
        if not os.path.exists(mcjar): print(f'=== {v}: MC jar 缺失'); all_ok=False; continue
        all_ok &= verify(jar, mcjar, v,
                         tiny=os.path.join(LOOM, v, 'intermediary-v2.tiny') if v.startswith('1.') else None)
    print('\n总结:', '全部 PASS ✅' if all_ok else '存在 FAIL ❌')
    sys.exit(0 if all_ok else 1)

if __name__ == '__main__':
    main()
