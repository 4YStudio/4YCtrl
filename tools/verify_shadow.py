#!/usr/bin/env python3
"""校验 dist/ 里每个 jar 的 @Shadow 是否真能在目标类**自身上**找到对应成员。

为什么需要这个脚本（真实事故）：
    Mixin 的 @Shadow **只在目标类自己的字段/方法表里查，不沿继承链找**。
    曾经在 KeyboardInputMixin 里写 @Shadow protected Vec2 moveVector，
    而 1.21.5+ / 26.x 的 moveVector 声明在父类（Input / ClientInput），
    于是：标题界面、设置界面、HUD、虚拟光标全部正常 —— 因为 KeyboardInput
    只在「进入世界」时才被加载 —— 一进世界立刻
        InvalidMixinException: @Shadow field moveVector was not located in
        the target class ... No refMap loaded.
    而 tools/verify_mixins.py 当时只看 @Inject 的 method 与 @Mixin 的目标类，
    @Shadow 一个都不查，所以 12 个版本全绿却仍会崩。

本脚本做的事：
    对每个 mixin 类，取出 @Shadow 的字段名/方法名，映射到该 MC 版本的混淆名，
    然后只在该 @Mixin 目标类自身（不下钻父类）里查。找不到就 FAIL。

用法:
    python3 tools/verify_shadow.py            # 校验 dist/ 全部版本
    python3 tools/verify_shadow.py 1.21.11    # 只校验指定版本
    python3 tools/verify_shadow.py --src      # 顺便扫源码树里残留的 @Shadow（更早预警）
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_mixins import load_tiny, javap, strip_L, members_and_super  # noqa: E402

LOOM = os.path.expanduser('~/.gradle/caches/fabric-loom')
VERSIONS = ['1.20.1', '1.20.2', '1.20.4', '1.20.6', '1.21.1', '1.21.4',
            '1.21.5', '1.21.8', '1.21.11', '26.1', '26.2', '26.3']


def shadow_members(out):
    """从 javap -v 输出里取 (shadow 字段名集合, shadow 方法名集合)。

    javap 输出形如（注意 jar 里的名字已经被 remap 成 intermediary，不带 refmap）：
        private double field_1789;
          descriptor: D
          flags: (0x0002) ACC_PRIVATE
          RuntimeVisibleAnnotations:
            0: #11()
              org.spongepowered.asm.mixin.Shadow
    显式写 @Shadow("xxx") 时注解会展开成：
              org.spongepowered.asm.mixin.Shadow(
                value="xxx"
              )
    """
    fields, methods = set(), set()
    cur = None          # 当前成员：(is_method, 名字)
    for line in out.splitlines():
        st = line.strip()
        # 成员声明行：'private double field_1789;' 或 'private void foo(Args);'
        m = re.match(r'^(?:public|protected|private|static|final|abstract|native|synchronized|volatile|transient)[\w\s<>\[\].$,]*?\s([\w$]+)\s*(\(.*)?;$', st)
        if m and not st.startswith('}'):
            cur = (bool(m.group(2)), m.group(1))
            continue
        if cur is None:
            continue
        if st.startswith('org.spongepowered.asm.mixin.Shadow'):
            is_method, name = cur
            (methods if is_method else fields).add(name)
            cur = None
        elif st.startswith('org.spongepowered.asm.mixin.') or st.startswith('descriptor:'):
            # 别的注解/描述符：不打断（@Shadow 可能跟在几个注解之后）
            continue
    return fields, methods


def verify_one(version, src=False):
    jar = f'dist/fouryctrl-1.0.0+{version}.jar'
    mcjar = os.path.join(LOOM, version, 'minecraft-merged.jar')
    label = f'=== {version} ==='
    if not os.path.exists(jar):
        print(f'{label} jar 缺失'); return False
    if not os.path.exists(mcjar):
        print(f'{label} MC jar 缺失'); return False
    tiny = os.path.join(LOOM, version, 'intermediary-v2.tiny')
    cmap, mmap, fmap = load_tiny(tiny) if version.startswith('1.') else ({}, {}, {})

    print(label)
    ok = True
    with zipfile.ZipFile(jar) as z:
        classes = [n for n in z.namelist()
                   if n.startswith('com/fouryctrl/mixin/') and n.endswith('.class') and '$' not in n]
    for cls in sorted(classes):
        out = javap(jar, cls)
        sf, sm = shadow_members(out)
        if not sf and not sm:
            continue
        mt = re.search(r'org\.spongepowered\.asm\.mixin\.Mixin\(\s*value=\[(.*?)\]\s*\)', out, re.S)
        if not mt:
            continue
        target = strip_L(re.findall(r'class (L[\w/$;]+)', mt.group(1))[0])
        short = os.path.basename(cls)

        if cmap and target.startswith('net/minecraft/class_'):
            self_obf = cmap.get(target)
            if self_obf is None:
                print(f'  ✗ {short}: {target} 不在 tiny 映射中'); ok = False; continue
            # @Shadow 只查目标类自身 —— 这正是运行期行为
            ms, fs, _sup = members_and_super(mcjar, self_obf)
            if ms is None:
                print(f'  ✗ {short}: 目标类 obf {self_obf} 不存在'); ok = False; continue
            miss = []
            for f in sorted(sf):
                cands = fmap.get((self_obf, f)) or set()
                if not (cands & fs):
                    miss.append(f'field {f}')
            for m in sorted(sm):
                cands = mmap.get((self_obf, m)) or set()
                if not (cands & ms):
                    miss.append(f'method {m}')
            if miss:
                print(f'  ✗ {short} → {target} (obf {self_obf}) 自身找不到: {miss}')
                ok = False
            else:
                print(f'  ✓ {short} → {target} (obf {self_obf}) @Shadow 自身可解析 '
                      f'{sorted(sf) + sorted(sm)}')
        else:
            ms, fs, _sup = members_and_super(mcjar, target)
            if ms is None:
                print(f'  ✗ {short}: 目标类 {target} 不存在'); ok = False; continue
            miss = [f'field {f}' for f in sorted(sf) if f not in fs]
            miss += [f'method {m}' for m in sorted(sm) if m not in ms]
            if miss:
                print(f'  ✗ {short} → {target} 自身找不到: {miss}')
                ok = False
            else:
                print(f'  ✓ {short} → {target} @Shadow 自身可解析 {sorted(sf) + sorted(sm)}')
    return ok


def scan_src():
    """扫源码里所有 @Shadow，提示每个字段/方法名 —— 便于人工核对是否落在父类。"""
    print('=== 源码 @Shadow 清单（需人工确认都在目标类自身）===')
    roots = ['src/main/java/com/fouryctrl/mixin', 'mc26/src/main/java/com/fouryctrl/mixin']
    found = 0
    for root in roots:
        for dp, _dn, fn in os.walk(root):
            for f in fn:
                if not f.endswith('.java'):
                    continue
                path = os.path.join(dp, f)
                txt = open(path, encoding='utf-8').read()
                for i, line in enumerate(txt.splitlines(), 1):
                    # 只看真正的注解行（排除 javadoc/行注释里提到的 @Shadow）
                    if line.strip().startswith('@Shadow'):
                        found += 1
                        print(f'  {os.path.relpath(path)}:{i}: {line.strip()}')
    if not found:
        print('  （无 —— 全部走 @Accessor / 父类实例访问）')


def main():
    args = [a for a in sys.argv[1:]]
    if '--src' in args:
        scan_src()
        args = [a for a in args if a != '--src']
        if not args:
            return 0
    targets = args or VERSIONS
    all_ok = True
    for v in targets:
        all_ok &= verify_one(v)
    print('\n总结:', '全部 PASS ✅' if all_ok else '存在 FAIL ❌')
    return 0 if all_ok else 1


if __name__ == '__main__':
    sys.exit(main())
