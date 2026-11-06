#!/usr/bin/env python3
"""给工程临时打/撤「无头自测脚手架」。

用途：CI / 本地要验证「打开模组界面会不会崩」时，靠人手点菜单进不去（要先进世界），
所以这里伪造一个「手柄已连接 + 右摇杆推满」的状态，并在第 40 tick 自动打开指定界面。

    python3 tools/selftest_patch.py add            # 两边工程都打上
    python3 tools/selftest_patch.py add root       # 只打根工程（1.20.x ~ 1.21.x）
    python3 tools/selftest_patch.py add mc26       # 只打 mc26 工程（26.x）
    python3 tools/selftest_patch.py remove         # 全部撤销
    python3 tools/selftest_patch.py status         # 看当前是否已打

运行期用环境变量选场景：FOURY_SELFTEST=settings|cursor|bindings
  settings  打开 SettingsScreen（验证文字渲染 + 背景 blur 不重复）
  cursor    同上，并推右摇杆 → 验证虚拟光标（crosshair）画得出来
  bindings  打开 BindingsScreen

打完必须撤销（tools/headless_smoke.sh 会自动 trap 撤销）。
脚手架只加在 4 个位置，remove 后与原始源码逐字节一致。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# ---------------------------------------------------------------- 根工程（yarn 名）

R_GPM = os.path.join(ROOT, 'src/main/java/com/fouryctrl/input/GamepadManager.java')
R_4Y = os.path.join(ROOT, 'src/main/java/com/fouryctrl/FourYCtrl.java')

R_GPM_FIELDS_OLD = '    private boolean shutdown;'
R_GPM_FIELDS_NEW = '''    private boolean shutdown;

    // TEMP-SELFTEST（tools/selftest_patch.py 加的，验证完必须删）
    public boolean selftestFake;
    public float selftestStickX;
    public float selftestStickLY;'''

R_SYNC_OLD = '''    private void syncFromPoller(MinecraftClient client) {
        snapshotConnected = poller.snapshot(tickAxes, tickButtons);'''
R_SYNC_NEW = '''    private void syncFromPoller(MinecraftClient client) {
        // TEMP-SELFTEST
        if (selftestFake) {
            snapshotConnected = true;
            snapshotAxisCount = 4;
            java.util.Arrays.fill(tickAxes, 0f);
            if (selftestStickX != 0f) {
                tickAxes[Math.max(0, cfg.axisRX)] = selftestStickX;
            }
            if (selftestStickLY != 0f) {
                tickAxes[Math.max(0, cfg.axisLY)] = selftestStickLY;
            }
            return;
        }
        snapshotConnected = poller.snapshot(tickAxes, tickButtons);'''

R_TICK_OLD = '''                Keybinds.tick(client);
                gamepad.endTick(client);'''
R_TICK_NEW = '''                Keybinds.tick(client);
                // TEMP-SELFTEST
                if (System.getenv("FOURY_SELFTEST") != null) {
                    selftest(client);
                }
                gamepad.endTick(client);'''

R_METHOD_OLD = '    private static void crosshair(DrawContext context, int x, int y, int color, int pad) {'
R_METHOD_NEW = '''    // TEMP-SELFTEST
    private static int selftestTicks = 0;

    /**
     * TEMP-SELFTEST：强制加载「只有进世界才会被加载」的类。
     * KeyboardInput 在标题界面永远不会被 new，所以 @Shadow / @Inject
     * 写错时前面所有冒烟测试都是绿的，一进世界才崩（这就是当初漏掉的那个 bug）。
     * 这里主动 Class.forName 一下，让 mixin 在标题界面就完成 transform。
     */
    private static void selftestLoadWorldOnlyClasses() {
        String[] names = {
                "net.minecraft.client.input.KeyboardInput",
                "net.minecraft.client.Mouse"
        };
        for (String n : names) {
            try {
                Class.forName(n, false, Thread.currentThread().getContextClassLoader());
                LOG.info("[selftest] classload OK {}", n);
            } catch (Throwable t) {
                LOG.error("[selftest] CLASSLOAD-FAIL {} -> {}", n, t.toString(), t);
            }
        }
    }

    private static void selftest(MinecraftClient client) {
        selftestTicks++;
        gamepad.selftestFake = true;
        if (selftestTicks == 20) {
            selftestLoadWorldOnlyClasses();
        }
        String what = System.getenv("FOURY_SELFTEST");
        if ("cursor".equals(what) && selftestTicks > 41) {
            gamepad.selftestStickX = 1.0f;
        }
        if ("move".equals(what) && selftestTicks > 41) {
            // moveForward() = -leftY，所以 LY=-1 表示向前推满
            gamepad.selftestStickLY = -1.0f;
        }
        if (selftestTicks == 40) {
            LOG.info("[selftest] 开界面 what={}", what);
            if ("move".equals(what)) {
                client.setScreen(null);
            } else if ("cursor".equals(what) || "settings".equals(what)) {
                client.setScreen(new com.fouryctrl.client.SettingsScreen(null));
            } else if ("bindings".equals(what)) {
                client.setScreen(new com.fouryctrl.client.BindingsScreen(null));
            }
        }
        if (selftestTicks % 20 == 0) {
            double px = client.player == null ? 0 : client.player.getX();
            double pz = client.player == null ? 0 : client.player.getZ();
            LOG.info("[selftest] tick={} screen={} connected={} active={} cursorVisible={} cursor=({},{}) pos=({},{}) driving={} moveFwd={}",
                    selftestTicks, client.currentScreen,
                    gamepad.state().connected, gamepad.isActive(), gamepad.isCursorVisible(),
                    gamepad.cursorX(), gamepad.cursorY(), px, pz,
                    client.player != null && gamepad.isDriving(client), gamepad.moveForward());
        }
    }

    private static void crosshair(DrawContext context, int x, int y, int color, int pad) {'''

# 26.x 里 syncFromPoller 的参数类型是 Minecraft 而不是 MinecraftClient
R_SYNC_OLD_26 = R_SYNC_OLD.replace('MinecraftClient client', 'Minecraft client')
R_SYNC_NEW_26 = R_SYNC_NEW.replace('MinecraftClient client', 'Minecraft client')

# ---------------------------------------------------------------- mc26 工程（官方名）

M_GPM = os.path.join(ROOT, 'mc26/src/main/java/com/fouryctrl/input/GamepadManager.java')
M_4Y = os.path.join(ROOT, 'mc26/src/main/java/com/fouryctrl/FourYCtrl.java')

M_METHOD_OLD = '    private static void crosshair(GuiGraphicsExtractor context, int x, int y, int color, int pad) {'
M_METHOD_NEW = '''    // TEMP-SELFTEST
    private static int selftestTicks = 0;

    /** 见根工程同名方法：强制加载只有进世界才会被加载的类，让 mixin 提前 transform。 */
    private static void selftestLoadWorldOnlyClasses() {
        String[] names = {
                "net.minecraft.client.player.KeyboardInput",
                "net.minecraft.client.MouseHandler"
        };
        for (String n : names) {
            try {
                Class.forName(n, false, Thread.currentThread().getContextClassLoader());
                LOG.info("[selftest] classload OK {}", n);
            } catch (Throwable t) {
                LOG.error("[selftest] CLASSLOAD-FAIL {} -> {}", n, t.toString(), t);
            }
        }
    }

    private static void selftest(Minecraft client) {
        selftestTicks++;
        gamepad.selftestFake = true;
        if (selftestTicks == 20) {
            selftestLoadWorldOnlyClasses();
        }
        String what = System.getenv("FOURY_SELFTEST");
        if ("cursor".equals(what) && selftestTicks > 41) {
            gamepad.selftestStickX = 1.0f;
        }
        if ("move".equals(what) && selftestTicks > 41) {
            gamepad.selftestStickLY = -1.0f;
        }
        if (selftestTicks == 40) {
            LOG.info("[selftest] 开界面 what={}", what);
            if ("move".equals(what)) {
                Compat.openScreen(client, null);
            } else if ("cursor".equals(what) || "settings".equals(what)) {
                Compat.openScreen(client, new com.fouryctrl.client.SettingsScreen(null));
            } else if ("bindings".equals(what)) {
                Compat.openScreen(client, new com.fouryctrl.client.BindingsScreen(null));
            }
        }
        if (selftestTicks % 20 == 0) {
            double px = client.player == null ? 0 : client.player.position().x;
            double pz = client.player == null ? 0 : client.player.position().z;
            LOG.info("[selftest] tick={} screen={} connected={} active={} cursorVisible={} cursor=({},{}) pos=({},{}) driving={} moveFwd={}",
                    selftestTicks, Compat.currentScreen(client),
                    gamepad.state().connected, gamepad.isActive(), gamepad.isCursorVisible(),
                    gamepad.cursorX(), gamepad.cursorY(), px, pz,
                    client.player != null && gamepad.isDriving(client), gamepad.moveForward());
        }
    }

    private static void crosshair(GuiGraphicsExtractor context, int x, int y, int color, int pad) {'''


def _pw(path):
    return path.replace(ROOT + os.sep, '')


def _patch(path, pairs):
    s = open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, f'{_pw(path)}: 找不到锚点\n{old[:120]}'
        assert s.count(old) == 1, f'{_pw(path)}: 锚点不唯一\n{old[:120]}'
        s = s.replace(old, new, 1)
    open(path, 'w', encoding='utf-8').write(s)


def _unpatch(path, pairs):
    s = open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert new in s, f'{_pw(path)}: 找不到已打的脚手架\n{new[:120]}'
        s = s.replace(new, old, 1)
    open(path, 'w', encoding='utf-8').write(s)


def add_root():
    _patch(R_GPM, [(R_GPM_FIELDS_OLD, R_GPM_FIELDS_NEW),
                   ('        if (!Sdl3.isReady() && !Sdl3.ensureReady()) {',
                    '        if (!selftestFake && !Sdl3.isReady() && !Sdl3.ensureReady()) {'),
                   (R_SYNC_OLD, R_SYNC_NEW)])
    _patch(R_4Y, [(R_TICK_OLD, R_TICK_NEW), (R_METHOD_OLD, R_METHOD_NEW)])


def remove_root():
    _unpatch(R_GPM, [(R_GPM_FIELDS_OLD, R_GPM_FIELDS_NEW),
                     ('        if (!Sdl3.isReady() && !Sdl3.ensureReady()) {',
                      '        if (!selftestFake && !Sdl3.isReady() && !Sdl3.ensureReady()) {'),
                     (R_SYNC_OLD, R_SYNC_NEW)])
    _unpatch(R_4Y, [(R_TICK_OLD, R_TICK_NEW), (R_METHOD_OLD, R_METHOD_NEW)])


def add_mc26():
    _patch(M_GPM, [(R_GPM_FIELDS_OLD, R_GPM_FIELDS_NEW),
                   ('        if (!Sdl3.isReady() && !Sdl3.ensureReady()) {',
                    '        if (!selftestFake && !Sdl3.isReady() && !Sdl3.ensureReady()) {'),
                   (R_SYNC_OLD_26, R_SYNC_NEW_26)])
    _patch(M_4Y, [(R_TICK_OLD, R_TICK_NEW), (M_METHOD_OLD, M_METHOD_NEW)])


def remove_mc26():
    _unpatch(M_GPM, [(R_GPM_FIELDS_OLD, R_GPM_FIELDS_NEW),
                     ('        if (!Sdl3.isReady() && !Sdl3.ensureReady()) {',
                      '        if (!selftestFake && !Sdl3.isReady() && !Sdl3.ensureReady()) {'),
                     (R_SYNC_OLD_26, R_SYNC_NEW_26)])
    _unpatch(M_4Y, [(R_TICK_OLD, R_TICK_NEW), (M_METHOD_OLD, M_METHOD_NEW)])


def status():
    out = []
    for label, p in (('root/FourYCtrl', R_4Y), ('root/GamepadManager', R_GPM),
                     ('mc26/FourYCtrl', M_4Y), ('mc26/GamepadManager', M_GPM)):
        marked = 'selftest' in open(p, encoding='utf-8').read()
        out.append(f'{label}: {"已打脚手架" if marked else "干净"}')
    print('\n'.join(out))


PATCHERS = {
    'root': (add_root, remove_root),
    'mc26': (add_mc26, remove_mc26),
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ('add', 'remove', 'status'):
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    if cmd == 'status':
        status()
        return 0
    target = sys.argv[2] if len(sys.argv) > 2 else 'all'
    names = list(PATCHERS) if target == 'all' else [target]
    if any(n not in PATCHERS for n in names):
        print(f'未知目标 {target}（可选 root / mc26 / all）')
        return 2
    for n in names:
        PATCHERS[n][0 if cmd == 'add' else 1]()
        print(f'{cmd} {n}: ok')
    return 0


if __name__ == '__main__':
    sys.exit(main())
