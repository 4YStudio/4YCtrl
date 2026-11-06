# 4YCtrl

让 **FCL（Fold Craft Launcher）的「SDL 直通」模式**下的真实手柄，在 **Minecraft Java 1.20.1 ~ 1.21.11 / 26.1 ~ 26.3 (Fabric)** 里真正可用。

---

## 支持的版本

一份源码、一键出包，覆盖三个世代：

| 版本 | 工程 | 产物命名 | 备注 |
| --- | --- | --- | --- |
| 1.20.1 / 1.20.2 / 1.20.4 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.5 / 1.21.8 / 1.21.11 | `versions/<ver>/`（Stonecutter 共用 `src/`） | `fouryctrl-<modver>+<ver>.jar` | yarn 混淆名，需 Fabric Loader ≥ 0.15 |
| 26.1 / 26.1.1 / 26.1.2 | `mc26/versions/26.1/` | `fouryctrl-<modver>+26.1.jar` | 需 **Java 25+**；一个 jar 同时支持三个补丁版 |
| 26.2 | `mc26/versions/26.2/` | `fouryctrl-<modver>+26.2.jar` | 需 **Java 25+** |
| 26.3 | `mc26/versions/26.3/` | `fouryctrl-<modver>+26.3.jar` | 需 **Java 25+** |

> 26.x 起 Mojang 取消了混淆，也没有 yarn / intermediary 映射，**无法与 1.20.1~1.21.11 共用同一个源码树**，所以 `mc26/` 是独立 Gradle 工程（内部同样是 Stonecutter 版本轴：`mc26/versions/<ver>/`），共享 `sdl/`、`config/` 与资源文件（通过符号链接复用，避免两份实现漂移）。
>
> 26.1 / 26.1.1 / 26.1.2 三者字节码级几乎完全相同（26.1.1 只差 3 个与手柄无关的类，26.1.2 差 9 个 checkbox/reporting 相关类），所以共用 `26.1` 一个节点，产物里 `depends.minecraft` 写成 `>=26.1 <26.2`。

> 版本区间必须用 Fabric Loader 支持的语法（`>=X <Y` 或 `~X.Y`）。**不要写 Maven 风格的 `[X,Y)`**：Loader 会把它当成一个普通字符串版本去比较，导致「需要 X 版本，但已安装的 X 不对」这种自相矛盾的报错。

一键打包：

```bash
./package.sh            # 构建并收集全部 12 个版本到 dist/
./package.sh 1.21.11    # 只打某个 yarn 版本
./package.sh 26.2       # 只打某个 26.x 版本
./package.sh 26         # 打全部 26.x（= mc26）
./package.sh --list     # 列出可打的目标
./package.sh --no-build # 只收集已构建的 jar
```

产物在 `dist/`：`fouryctrl-*.jar` + `SHA256SUMS` + `README.txt`（尺寸/日期清单）。
`dist/`、各版本的 `build/` 与 `run/` 都是生成物，已在 `.gitignore` 里排除。

---

## 它解决什么问题

安卓上用 FCL 玩 Java 版 MC，手柄一直是痛点：

| 方案 | 结果 |
| --- | --- |
| FCL 自带「手柄映射」 | 摇杆被量化成 8 个方向（`FCLInput.handleLeftJoyStick` 用 `atan2` 每 45° 取一档），视角一顿一顿的，也就是常说的「灵敏度非常差」 |
| FCL「SDL 直通」+ 任何市面手柄模组 | 直通链路是断的，模组读不到数据 |
| Controlify 等模组 | 走 GLFW。FCL 游戏侧用的是 LWJGL 3.3.3 的 **stub**，`glfwGetJoystickAxes()` 返回容量为 0 的 buffer，永远空 |

4YCtrl 不走 GLFW，也不改 FCL，而是在游戏进程内**直接调 SDL3**，把这条直通链路接通。

## 为什么直通链路原本是断的

两个断点，缺一不可：

1. **SDL 的手柄子系统从未被初始化。**
   `SdlBridge.initializeControllerSubsystems()` 这个函数在 FCL 里只有声明，全仓没有任何调用点。
   于是 `SDLControllerManager.mJoysticks` 永远是空表，FCL 转发过来的每个 `MotionEvent`
   在 `handleMotionEvent()` 里因为 `getJoystick(deviceId)` 为 null 被静默丢弃。
2. **`SDLControllerManager.pollInputDevices()` 也没人调。**
   它负责用 `InputDevice.getDeviceIds()` 把设备注册进 SDL（`nativeAddJoystick`）。

而初始化只有一条安全路径：调用 **`SDL_InitSubSystem`**。
FCL 用 bytehook 把 `dlsym` 全局劫持了（`native_hooks/sdl_hook.c` + `sdl_dlopen_hook.c`），
`SDL_InitSubSystem` 在它的白名单里，代理会先 `notifyLauncher(ACTION_INIT_LAUNCHER_INTEGRATION)`，
让启动器侧 `System.loadLibrary("SDL3")` —— 此时 SDL 的 `JNI_OnLoad` 才会用 ART 的 JavaVM
去 `RegisterNatives`，`SDLControllerManager` 的 native 方法才真正绑上。
直接调真实现会因 `Android_JNI_GetEnv()` 返回 NULL 而在 JNI 回调处 SIGSEGV。

> 所以 4YCtrl 会**先探测代理是否存在**：调一次 `SDL_SetWindowSize(NULL,0,0)`。
> 代理实现恒返回 `true`，真实现返回 `false`。没有代理就不初始化、只打日志，绝不崩进程。

链路接通后：

```
手柄 → Android MotionEvent → FCL SDL 直通 → SDL_InitSubSystem 已就绪
     → SDL 内部 joystick 状态 → 独立轮询线程（约 166Hz）读 SDL_GetJoystickAxis/Button → 发布快照
     → 客户端线程读快照 → 写 Minecraft 的 Mouse.cursorDelta / Input.movementForward · KeyBinding
```

> **为什么读写要分线程**：FCL 的 SDL3 是 Android 后端，手柄事件由 ART 侧 `SDLControllerManager.onNativeJoy()`
> 写进 SDL 的 joystick 状态，而所有 joystick API 共享同一把锁（`SDL_LockJoysticks`）。
> 一旦这把锁被 ART 侧或驱动长时间持有，在客户端线程读轴的每一次调用都会阻塞——最坏情况是一帧卡几十毫秒，
> 表现为「插上手柄进世界就锁 10 帧」这种整数量级的卡顿；拔掉手柄后注入点立即早退，帧率瞬间恢复。
> 现在即使 SDL 内部被锁住，被阻塞的也只是那个守护线程（读到的数据会变旧，但视角/光标继续用上一份快照）。
> 约定：轮询线程先在自己的私有缓冲区里做完所有 JNI，再用一次 `System.arraycopy` 发布——**绝不可以在持锁期间做 JNI**，
> 否则客户端线程拷快照时又会被锁住，等于没修。

## 安装与使用

1. 装 **Fabric Loader** + **Fabric API**，版本要和你玩的 MC 对上（见上面的「支持的版本」）。
2. 把对应版本的 `fouryctrl-<modver>+<mcver>.jar` 放进 `mods/`（放错版本的 jar 会被 Loader 直接拒载）。
3. 在 FCL 里把 **手柄连接模式设为「SDL 直通」（SDL Direct）**。这一步必须做：其他模式下 FCL 会在事件层就把手柄吞掉。
4. 进游戏，连上手柄。日志里应出现：

```
[4YCtrl] SDL3 已就绪（句柄 0x...）
[4YCtrl] SDL 手柄子系统初始化成功（flags=0x6200）
[4YCtrl] 已连接手柄 #0: Xbox Wireless Controller
```

看到第三行就成功了。

### 游戏内开关

安卓上没有物理键盘，所以开关给了三条路（**主要用前两条**）：

| 入口 | 作用 |
| --- | --- |
| **暂停菜单右上角的 `4YCtrl: 开/关` 按钮** | 总开关，点一下就切。会写回配置 |
| **暂停菜单右上角的 `4YCtrl 设置` 按钮** | 打开设置界面：死区、视角速度、移动倍率、反转 Y，拖动即时生效 |
| **长按 L3 + R3 约 0.75 秒** | 总开关。停用后仍可用它重新启用（随时生效，不需要先打开菜单） |
| `F8` | 总开关（仅 PC 调试用） |
| `F9` | 开关调试日志（每 20 tick 打一行摇杆/按键快照，报 bug 时请开这个） |

### 界面内手柄操作（GUI 光标）

打开任何界面（背包、聊天、暂停菜单……）时，**右摇杆变成虚拟鼠标光标**，
LT（攻击键）= 左键、RT（使用键）= 右键。光标初始在屏幕中心，进界面就显示。

光标形状是**实心细十字**（加号，中心像素就是命中点），笔画粗细（1–3px）与臂长（3–12px）
可在设置界面调。速度是「像素/秒」、带平方曲线（轻推慢移、推满全速），
并且是**按渲染帧率逐帧推进**（不是按 20Hz 的游戏 tick），所以移动是连续的、不会一顿一顿。
灵敏度滑块默认 900 px/s。

非容器界面（选项、列表、暂停菜单）里还能用**十字键微调光标**：按一下挪 2px，
按住连挪——比摇杆更容易点准小控件。

**滚轮**：LB 向上滚、RB 向下滚（首按滚一格，按住连滚）。列表、设置页、创造模式物品栏
都能用；容器界面里 LB/RB 另有用途（丢整叠/丢光标物品），所以滚轮只在非容器界面生效。

光标直接写回游戏本体的鼠标坐标（`Mouse.x/y`，按窗口物理像素换算），
所以 hover 高亮、tooltip、点击命中三者永远一致——看到光标在哪，点下去就命中哪，
不会出现「自绘光标画在 A 点、实际命中在 B 点」的偏移。

**容器界面（背包/箱子/工作台/熔炉）里右摇杆照样能自由移动光标**，光标压到哪个格子
data 高亮就跟着哪个格子；同时十字键还能逐格精准跳（按方向找最近的格子），两套并行：
摇杆用来大范围挪，十字键用来一格一格对。

### 配方书（工作台 / 熔炉）

打开工作台或熔炉的配方书后，**光标推到左侧书区域时按键含义自动切换**（区域判定，
不需要手动切模式）：

| 手柄 | 书内操作 |
| --- | --- |
| 右摇杆 | 自由移动光标 |
| 十字键 | 在「配方格 / 翻页 / 分类页签 / 搜索框 / 过滤按钮」之间按方向跳，坐标吸附 |
| A | 在光标处点一下（选配方 / 点分类 / 聚焦搜索框） |
| B | 收起配方书 |
| LT / RT | 上一页 / 下一页 |
| LB / RB | 上一个 / 下一个分类 |

选中的配方直接走原版 `clickRecipe`，所以「点一下预览、Shift 一次做满」的行为和 PC 一致。
光标画在书的上层（配方书自己会把内容抬到 z=100，我们在 z=300 之上绘制），
**不会被书盖住**。铁砧/切石机没有配方书，区域判定自然不生效。

点击时手柄会短震一下（装了可震手柄时）。不想用的话在设置界面把「GUI 光标」关掉。

### 文字输入（聊天 / 搜索框打字）

安卓上没有实体键盘，原版聊天框和配方书搜索框要靠宿主的系统输入法（IME）。
模组**不再自带屏幕键盘**：之前在界面里自绘了一套 4 行 QWERTY，只能在文本框里塞 ASCII，
中文打不出来，还会在弹窗时挡输入框、抢走十字键和摇杆、让光标失灵，实际是净负担。

现在直接用宿主输入法：FCL 里是右菜单的键盘按钮，或默认快捷键 **右 Shift + Enter**
（`FCLInput.handleKeyEvent` → `TouchCharInput.switchKeyboardState()` → `showSoftInput`）。
手柄此时仍然可以推光标、点按钮，两者不冲突。

### 诊断命令

装了 Fabric API 后可用客户端命令（不需要开作弊，单机/服务器都能用）：

| 命令 | 作用 |
| --- | --- |
| `/4y diag` | 一屏诊断：平台、SDL 就绪状态与链路文字、活动手柄名/轴数、**全部轴实时读数**、当前按下键、震动支持、完整绑定表、关键配置 |
| `/4y rumble` | 强制震 400ms，确认震动链路通不通 |
| `/4y nextpad` | 多手柄时切到下一个活动手柄（按轴数择优） |
| `/4y savepad` | 把当前轴序号 + 绑定存成「按手柄名」的设备档，下次插同一只手柄自动套用 |
| `/4y perf` | 打开**耗时探针**并在聊天栏打印当前窗口的统计（详见下节） |
| `/4y off` | 立刻关掉探针（日志每 2 秒刷一行，看腻了就关） |

`/4y diag` 是排查「轴错位」「按键没反应」最快的一条路：不用翻 logcat，也不用进设置界面。

### 耗时探针（`/4y perf`）

安卓上「插手柄后掉到 10 帧」「界面里一顿一顿」这类问题，静态看代码永远猜不准是哪一次 JNI、
哪一段绘制在吃时间，而 adb 又不好拿。探针把每一段的**平均 / 最大耗时**按秒打进日志：

```
[perf] fps=57 tick=0.31(1.2)x18 pump=0.04(0.1)x4 read=0.02(0.3)x342 look=0.15(0.9)x57 frame=16.7(24.0)x57
```

- **默认关**：`/4y perf` 打开后**采集 180 秒自动关回**（拿到一次现场数据就够了，常驻会淹没其它日志），
  中途想提前关就 `/4y off`。开会话时会立刻打印一次聊天栏快照，方便截图。
- 段位：`tick` `pump` `poll` `read` `toggle` `radial` `gui` `apply` `trig` `look` `cursor` `hud`
  `axis` `frame` `limit` `screen` `hints` `rmmenu`。关掉时所有采样点立刻返回，零开销。
- `frame` 段是**真实帧间隔**（由 `Mouse.updateMouse` 头部每次量一次），直接对照 `fps` 看谁在吃帧。
- 如果开着界面但 `screen`/`hints`/`rmmenu` 全是 0 次，日志会补一句警告：说明这个平台的帧循环
  在界面里**绕过了 `Screen.renderWithTooltip`**（FCL 触屏光标模式就是这样），此时界面掉帧
  与本模组的界面绘制无关，排查方向要转向帧循环本身（其它 mod、启动器、GPU 驱动）。
- 静态读代码看不出问题时先跑探针再猜：`look` 大 = 视角计算，`read`/`poll` 大 = SDL 轮询，
  `screen`/`hints` 大 = 我们自己画的；`frame` 大而各段都很小 = 卡在模组之外。

### 震动

除了「攻击/使用」与界面内点击，还有四类场景可以单独开关（默认只有饥饿关着）：

| 场景 | 触发条件 |
| --- | --- |
| 攻击 / 使用 | 扳机过死区的瞬间（基础反馈，随强度滑块） |
| 拾取物品 | 背包物品总数增加 |
| 耐久告危 | 主手可损物品剩余耐久 ≤ 10%，**每次换物品只提醒一次** |
| 受伤 | `hurtTime` 上升 |
| 饥饿 | 饥饿值下降（默认关：饿得太频繁会烦） |

强度可在设置界面调（0.05–1.0）。震动一次下发完整时长（`tick 数 × 50ms`），
不再逐 tick 重发 60ms 短震——后者在安卓上会让马达反复启停、听起来是「哒哒哒」的碎震。
需要手柄本身支持震动且 FCL 的 SDL 把它映射到了系统 Vibrator；震动函数解析不到时
设置界面不会显示这一项。`/4y rumble` 可以强制震一下验证链路。

连接/断开手柄、切换开关都会在动作栏（物品栏上方）提示一句。连接提示会带电量
（`Xbox Wireless Controller (78%)`），设置界面顶部的连接行同样显示电量——
数据来自 `SDL_GetJoystickPowerInfo`，读的是系统上报的电池广播；有线或无电量上报的手柄显示原样。

### 游戏内设置界面

暂停菜单 → 右上角「4YCtrl 设置」。里面能调：左/右摇杆死区、扳机死区、视角速度、移动速度倍率、
**摇杆响应曲线**（0.5–3，>1 小幅度更细腻）、**视角加速度**（1–3，>1 轻推慢转、推满快转）、
反转视角 Y、潜行/疾跑触发方式（**切换/按住**，默认切换）、GUI 光标开关、光标灵敏度、
光标笔画粗细/臂长、**十字键语义**（微调光标 / 控件间吸附跳转）、
按键提示开关与**提示位置**（底部/顶部 × 两侧/仅左/仅右，共 6 种）、**提示详细度**（全部/关键/不显示）、
**径向动作轮盘开关**、**轴自动识别**、**实时轴**（每帧用最新摇杆读数）、
**帧率守卫**（忽略外部模组异常低的限帧请求）、震动强度与四类震动场景、
以及「按键绑定 ›」入口（详见下一节）。
界面带**纵向滚动条**（滚轮/拖动右侧滑块），按钮始终保持舒适大小不再挤压成一屏。
另有「恢复默认」「重新检测」「完成」。拖动即时生效（不用退出界面就能试手感），关掉界面时才写进 `config/fouryctrl.json`。

装了 **ModMenu** 的话，「模组菜单 → 4YCtrl → 设置」会直接打开同一个界面（可选依赖，没装时不加载那个类）。

### 底部按键提示（Controlify 式）

打开背包/箱子等界面时，屏幕角落会叠一列速查：「A 打开背包 · B 丢弃 · LT 攻击 · RT 使用 ·
LB/RB 切换快捷栏 · Start 暂停」。按钮徽章按你**当前的实际绑定**反查显示——改了键位提示自动跟随；
其他界面（暂停菜单、聊天……）显示通用导航条（含十字键微调、LB/RB 滚轮）。

提示分**三档**（设置界面里切）：

| 档位 | 效果 |
| --- | --- |
| 全部显示 | 所有绑定都列，界面里连 A/B 这种基础操作也不省略 |
| 只显示关键键（默认） | 省略天天按的常用键（跳/背包/快捷栏/暂停、界面内的 A/B 点击与关闭），只留状态、物品与进阶操作 |
| 不显示 | 全关（等价于总开关关闭，但保留其它设置） |

提示内容还会**跟着状态变**：潜行/疾跑开关状态、主手/副手物品名与空手、手持物是食物/
方块/工具时使用键叫什么（吃/放/挖）；容器里光标推到配方书区域时，A/B/LT/RT/LB/RB
会换成书内操作的说法，书关掉又自动换回来。

显示位置可在设置里循环切换 6 种锚点：底部/顶部 × 两侧/仅左/仅右。
不同手机长宽比差别很大：底部两列可能压住物品栏与血条，顶部可能压住状态效果，
所以留了「只显示一侧」的选项把另一侧完全让给画面。

**「重新检测」**用在两种时序情况下：

- 游戏已经跑起来了才连上手柄（蓝牙刚配对、USB 刚插上）；
- FCL 侧的 SDL2 比我们的初始化晚了一步，第一次 `SDL_InitSubSystem` 失败了。

初始化只做一次（失败后不再每 tick 重试，否则日志会被刷满），所以这两种情况原本只能重启游戏。
按下这颗按钮会丢掉之前的结论、关掉当前手柄，下一 tick 重走完整链路。

连接状态下面那行小字是**链路状态**（窗口够高时才显示），它把「手柄未连接」拆成了具体原因，
例如 `未检测到 FCL 的 SDL 初始化代理（不在 FCL 内，或 FCL 版本过旧）`、
`dlopen("libSDL3.so") 失败：…`、`SDL_InitSubSystem 失败：…`。
手机上翻 logcat 很麻烦，这行字基本能覆盖八成排错。

界面底部那一行 `LX0=0.02 LY1=-0.01 RX2=...` 是**实时轴读数**，用来排查轴错位：
推一下左摇杆，看哪个轴在动、上面标的标签对不对。标着 `LX`/`LY`/`RX`/`RY`/`LT`/`RT` 的就是配置里
`axisLX..axisRT` 当前指向的轴。如果推左摇杆时动的是 `RX`，那就得去 JSON 里改轴序号（这一项故意没做成滑块，
配错会直接不可用，不适合误触）。

死区调法：松手时看轴读数。数值在 ±0.05 以内就够用；漂到 ±0.1 就把死区提到 0.12 左右。
死区是径向的且做了重新归一化，所以调大不会让摇杆「变钝」，只是把中心的死区挖掉。

### 按键绑定

设置界面 → 「按键绑定 ›」。每个手柄按钮一行，**点一下循环切换到下一个动作**
（右键点往回退），选到「无」= 解绑。手柄上按住哪个按钮，对应行会亮一条绿线，
方便确认「界面里的 A（南）就是我手柄上的哪个键」。底部「恢复默认绑定」回到下表的 FCL 风格默认值。

潜行/疾跑的**切换/按住**在设置界面里调（默认切换式：点一下生效、再点一下解除）。
切换状态在停用模组、手柄断开或重新检测时自动复位，不会出现「重进后人是蹲着的却找不到原因」。

#### 绑键盘键 / 其它模组的键

每个按钮那一行右侧还有一个 **「⌨ 键」** 列：点一下弹出采集界面，**按下键盘上的任意键**（或鼠标左/中/右键）
就会把这个手柄按钮绑到**那个 `KeyBinding`** 上；Esc 取消、右键点该列直接清空。

这一层解决的是「本模组只有十来个动作，但我还想用手柄开小地图/一键整理/投影/回放」的问题：
MC 里所有这类功能（包括其它 mod 注册的）都挂在 `KeyBinding` 上，而 `KeyBinding.onKeyPressed(InputUtil.Key)`
是按翻译键查表的静态方法 —— 只要构造出同一个 `Key` 对象，就等于替玩家按下了那个键，不需要反射。
配置里存的是翻译键（`key.keyboard.f` / `key.mouse.left`），落在 `keyBindings` 字段。
**只在游戏内（无界面）转发**：界面里手柄按钮仍然只做导航，不会误触发别的 mod 的功能。

### 径向动作轮盘

手柄按钮就那么十几个，还都被基础操作（跳/背包/丢弃/快捷栏）占满了。剩下那些「偶尔用一次」
的动作（聊天、切视角、玩家列表、隐藏 HUD）如果都塞进十字键，就会和界面内导航抢同一个键 ——
一个键两种身份，玩家记不住。

轮盘把它们收进一个「按住才出现」的环：**按住 BACK → 环弹出 → 右摇杆指方向 → 松手执行**。
平时那枚按钮什么都不干，十字键就能安心做导航。槽位从正上方开始顺时针排，内容写在
`radialActions` 里（默认 `chat,perspective,player_list,hide_hud`），设置界面里有开关。

- 按住不足 120ms 视为误触，不执行（避免弹一下就把聊天框打开）。
- 只有「按一下触发一次」的动作适合放进来；跳跃/攻击这种持续型就算塞进配置也会被忽略，
  否则松手时会凭空跳一下。
- `/4y diag` 里有一行「轮盘：」显示当前槽位。

### 多手柄与设备档

`/4y nextpad` 在多只手柄之间切换（按轴数择优）。切到某只手柄后，如果它的**轴序号不一样**
（推左摇杆却侧着走、扳机反了），调好之后用 `/4y savepad` 把「轴序号 + 绑定」存成一份档；
下次插同一只手柄自动套用，不用再改 JSON。

档按 SDL 报上来的**手柄名**做键，不是 GUID —— `SDL_GetJoystickGUID` 按值返回 16 字节结构体，
LWJGL 的 `JNI.invoke*` 没有返回结构体的变体（读出来必然是垃圾或崩溃）。代价是同型号两只手柄共
用一份档，手机场景下够用。档存在 `fouryctrl.json` 的 `deviceProfiles` 里。

## 默认按键

默认值与 FCL 自带手柄映射风格一致：

| 手柄 | 动作 |
| --- | --- |
| 左摇杆 | 移动（**模拟量**，不是 8 方向） |
| 右摇杆 | 视角；界面内为 GUI 光标 |
| LT | 攻击（模拟量扳机；界面内 = 鼠标左键） |
| RT | 使用 / 放置（模拟量扳机；界面内 = 鼠标右键） |
| A / SOUTH | 跳跃 |
| B / EAST | 丢弃物品 |
| X / WEST | 背包 |
| Y / NORTH | 交换副手 |
| LB | 快捷栏 ←（上一格） |
| RB | 快捷栏 →（下一格） |
| L3 | 疾跑（默认切换式） |
| R3 | 潜行（默认切换式） |
| START | 暂停菜单 |
| 十字键 | 默认未绑定（容器/配方书里自动用于导航）；可在按键绑定里绑成动作 |
| BACK / GUIDE | 默认未绑定（可在按键绑定里自选；轮盘开启时 BACK 用作轮盘触发键） |

十字键可以绑的有：聊天、切换视角、玩家列表、隐藏 HUD 等（设置界面 → 按键绑定 → 点十字键那一行循环切）。
一旦某个十字键被绑了动作，它就不再参与界面内光标微调/格子导航，不会互相撑。

之前版本（v1）的旧配置文件会在启动时自动迁移到这套新默认值（因为旧默认的绑定布局确实不合理）。

## 配置

`config/fouryctrl.json`，首次启动自动生成：

```json
{
  "configVersion": 2,
  "enabled": true,
  "autoInitSdl": true,
  "debug": false,
  "leftDeadzone": 0.15,
  "rightDeadzone": 0.12,
  "triggerDeadzone": 0.10,
  "lookSpeed": 260.0,
  "invertLookY": false,
  "moveSpeedScale": 1.0,
  "guiCursor": true,
  "guiCursorSpeed": 900.0,
  "cursorThickness": 1,
  "cursorLength": 6,
  "stickCurve": 1.0,
  "lookAcceleration": 1.0,
  "dpadMode": "cursor",
  "showButtonHints": true,
  "hintMode": "partial",
  "hintAnchor": "bottom_both",
  "radialMenu": false,
  "radialKey": "BACK",
  "radialActions": "chat,perspective,player_list,hide_hud",
  "toggleSneak": true,
  "toggleSprint": true,
  "rumbleEnabled": true,
  "rumbleStrength": 0.6,
  "rumblePickup": true,
  "rumbleDurability": true,
  "rumbleHurt": true,
  "rumbleHunger": false,
  "axisAutoDetect": true,
  "liveAxis": true,
  "ignoreExternalFpsLimit": true,
  "joystickPollTicks": 10,
  "axisLX": 0, "axisLY": 1, "axisRX": 2, "axisRY": 3, "axisLT": 4, "axisRT": 5,
  "triggerLeftAction": "attack",
  "triggerRightAction": "use",
  "bindings": { "SOUTH": "jump", "RIGHT_STICK": "sneak", "...": "..." },
  "keyBindings": { "DPAD_UP": "key.keyboard.m" },
  "deviceProfiles": {}
}
```

- **轴序号对不上**（推左摇杆人却侧着走、扳机反了）：改 `axisLX..axisRT`。
  SDL 的 Android 后端会按 `RangeComparator` 把轴重排成 X, Y, RX, RY, Z, RZ，所以默认是 0..5，但不同设备可能不同。
- **`autoInitSdl`**：是否允许模组自己去调 `SDL_InitSubSystem` 把 SDL 手柄子系统唤醒。
  默认 `true`。关掉它适合「FCL 自己已经把 SDL 初始化好了」的场景——此时模组只读不初始化。
- **`guiCursor` / `guiCursorSpeed`**：界面内虚拟光标开关与满推速度（像素/秒，平方曲线减速）。
- **`cursorThickness` / `cursorLength`**：光标十字的笔画粗细（1–3px）与单侧臂长（3–12px）。
- **`hintAnchor`**：按键提示位置，可选 `bottom_both` `bottom_left` `bottom_right` `top_both` `top_left` `top_right`。
- **`hintMode`**：提示详细程度，`all`（全部）/ `partial`（只留关键键，默认）/ `off`（不显示）。
- **`stickCurve` / `lookAcceleration`**：摇杆响应曲线与视角加速度（指数，1 = 线性）。调曲线不会改变满推时的上限。
- **`dpadMode`**：`cursor`（十字键微调光标）/ `focus`（在控件间吸附跳转，适合纯按钮菜单）。
- **`radialMenu` / `radialKey` / `radialActions`**：径向动作轮盘。按住 `radialKey`（默认 `BACK`）弹出环，
  右摇杆指方向、松手执行；`radialActions` 是逗号分隔的动作 id（最多 8 个，从正上方顺时针排）。
  只有「偶尔按一次」的动作适合放进来（持续型如跳跃/攻击放进来没有意义，会被忽略）。
  按住时间不足 120ms 视为误触，不执行。
- **`deviceProfiles`**：按手柄名保存的「轴序号 + 绑定」快照，由 `/4y savepad` 写入。
  连接时若命中会直接套用（同型号两只手柄共用一份）。
- **`axisAutoDetect`**：连接时自动识别轴位并校准摇杆中点。识别不自信（比如手柄躺着不动时扳机也在漂移）就保留原值，不会乱改。
- **`joystickPollTicks`**：多久查一次「有没有新手柄接入」（tick，1–100，默认 10；换算成 tick × 50ms 交给轮询线程）。已连接时每 tick 只查 `SDL_JoystickConnected`，不受影响。
- **`liveAxis`**：视角/GUI 光标是否每帧用最新摇杆读数（而不是每 tick 的 20Hz 采样）。
  默认 `true`。所有 `SDL_GetJoystickAxis` 都跑在独立轮询线程上（约 160Hz），客户端线程每帧只拷一份数组快照，
  所以开着几乎无成本；关掉后退回 tick 采样，视角最多滞后 50ms（表现为「起步一顿、停手还在滑」）。
- **`ignoreExternalFpsLimit`**：忽略「外部模组把帧上限压到异常低」的限帧请求，默认 `true`。
  本模组把 MC 的忙等自旋换成了真睡眠（FCL 的 `glfwWaitEventsTimeout` 是空实现），副作用是省电模组
  （典型 FPS Reducer）的 `window.setFramerateLimit(10)` 从此会真的生效，表现为「装上本模组就锁 10 帧、禁用就恢复」。
  开着时若发现窗口上限与玩家设定不一致且请求值异常低，就跳过限帧、保持满帧，并打一次 warn 指路（判定逻辑见 `util/FrameGuard`）。
  只有想「真的让省电模组生效」时才关掉它。
- **`keyBindings`**：手柄按钮 → **键盘/鼠标键翻译键** 的额外绑定（如 `"DPAD_UP": "key.keyboard.m"`），
  由设置界面「按键绑定 ›」的「⌨ 键」列采集。用来用手柄驱动**其它模组**的功能。
- **`configVersion`**：配置版本。v1 的旧默认绑定（LB=疾跑、B=潜行）与 FCL 习惯相反，
  读到 v1 会在启动时自动整体迁移到 v2（绑定重置为 FCL 风格，会打一行日志）。
- **`rumbleEnabled` / `rumbleStrength`**：震动开关与强度（0.05–1.0，在设置界面拖动会自动把开关打开）。
- **视角太快/太慢**：改 `lookSpeed`。它表示「满推时每秒转多少度」，MC 的鼠标灵敏度还会再叠上去。
- **死区**：`leftDeadzone` / `rightDeadzone` 是径向的，边缘做了重新归一化，不会出现「一推就跳到 0.15」的台阶。
- **扳机行为**：`triggerLeftAction` / `triggerRightAction` 可填 `use` / `attack` / `none` 等动作 id。
- **动作 id 全集**：`jump` `sneak` `sprint` `attack` `use` `inventory` `drop` `swap_offhand` `hotbar_prev` `hotbar_next` `pause` `chat` `perspective` `player_list` `hide_hud` `none`。
- **按钮名**：`SOUTH` `EAST` `WEST` `NORTH` `BACK` `GUIDE` `START` `LEFT_STICK` `RIGHT_STICK` `LEFT_SHOULDER` `RIGHT_SHOULDER` `DPAD_UP` `DPAD_DOWN` `DPAD_LEFT` `DPAD_RIGHT`。

## 限制

- **只支持 FCL 的 SDL 直通模式**，且必须是有 `sdl_hook` 的新版 FCL。其他启动器/原版启动会打一行错误日志后自行停用。
- **不支持 macOS / Windows 原生启动**（那些环境里 `libSDL3.so` 不存在）。代码会在 `dlopen` 失败时安静退出。
- **多手柄**：同时只驱动一个（按轴数择优，`/4y nextpad` 或重新检测可切换）。
  事件层仍然只读一个手柄的状态，不是真的 split-screen 多玩家。
- **26.x 尚未真机验证**：`mc26/` 的 26.1 / 26.2 / 26.3 都能编译出包，mixin 目标与字段名都已逐条核对（`javap` 级），但还没在 FCL 上跑过。已知风险点：26.x 的视角注入挂在
  `MouseHandler.handleAccumulatedMovement()`，其内部第一句就是 `if (!minecraft.isWindowActive()) return`（即 `Window.isFocused()`）。
  若 FCL 的 SDL 窗口在该环境下 `isFocused()` 恒为假，**视角注入会静默失效**（HUD 按钮提示与 GUI 光标不受影响，因为它们不走这条路径）。真机上若发现「手柄能点菜单但推摇杆不转视角」，就是这个原因。
- **按键提示是角落速查**（Controlify 式），不是逐个 widget 的高亮标注。
- **GUI 光标不模拟拖拽**：拖滑块可用点按跳到目标值（SliderWidget 点击即跳转）；逐帧拖拽留待后续。
- **模组不带输入法**：中文聊天必须用宿主自己的 IME（FCL 里是右菜单键盘按钮 / 右 Shift+Enter）。
  模组既不画软键盘，也不会去调 IME（游戏 JVM 里没有可用的 SDL 窗口句柄，调不动）。

## 排错

先看游戏内「4YCtrl 设置」界面：连接状态下面那行就是链路状态，先试「重新检测」，
大部分时序问题这样就解决了。还有问题再对照下表。

| 日志 / 界面文案 | 含义 / 处理 |
| --- | --- |
| `dlopen("libSDL3.so") 失败` | 不在 FCL 里，或 FCL 用的不是 SDL3 版本 |
| `未检测到 FCL 的 SDL 初始化代理` | FCL 版本过旧（没有 sdl_hook），或装的不是 FCL。模组已自行停用以避免崩溃 |
| `SDL_InitSubSystem(...) 失败：...` | 看后面的 SDL 错误串；通常是 FCL 侧 `loadLibrary("SDL2")` 失败。先试「重新检测」 |
| `SDL 手柄子系统初始化成功` 但一直 `未连接` | 手柄没被 Android 注册成 joystick；确认 FCL 是 SDL 直通模式，且手柄已配对/插好，然后按「重新检测」 |
| 轴读数全 0，按键有反应 | 轴序号配错了；推摇杆看哪个轴在动，改 `axisLX..axisRT` |
| 左推摇杆人却往右走 | 已在 v1.1 修复（vanilla 的 movementSideways 约定与摇杆相反）。若仍复现，看轴读数确认 LX 轴号是否正确 |
| 潜行/疾跑松不开 | v1.1 起默认为切换式：再按一下即解除。若改成按住式后卡住，开关界面一次会自动复位 |
| 潜行「站起来又立即潜行」 | SDL 按钮 down→up→down 抖动导致双翻转；v1.1 已加 2-tick 按钮去抖。若仍复现，开 debug 日志看 R3 的 btn 位是否在两次 tick 间回弹 |
| 界面里光标不动/看不见 | 「GUI 光标」开着的话推右摇杆；光标坐标每 tick 直接写回 `Mouse.x/y`，若仍不动发 debug 日志排查 |
| 进世界后帧率锁在 10 帧左右、手机发烫 | 已修（`RenderSystemMixin`）：原版限帧靠 `glfwWaitEventsTimeout` 阻塞，而 FCL 的 LWJGL stub 把它写成空方法 ⇒ 变成满核忙等。现在改成 `Thread.sleep` 睡到 deadline。另：手柄读轴已全部移到 `GamepadPoller` 守护线程，不会再被 SDL 的 joystick 锁拖住主线程 |
| 装上本模组才锁 10 帧、禁用就恢复 | 省电模组（FPS Reducer 之类）调 `window.setFramerateLimit(10)`，以前被忙等吃掉了、现在真的生效。设置里把「帧率守卫」开着（默认开），或关掉那个模组的省电功能 |
| 配方书区域光标被书盖住 | 已修（`ScreenMixin` 把光标抬到 z=300，书自身是 z=100） |
| 容器界面右摇杆推不动光标 | 已修（之前 `frameCursor` 在容器界面直接 return）；现在容器里摇杆与十字键并行 |
| 手感「一推就冲」或「太肉」 | 设置界面调「摇杆响应曲线」：>1 更细腻，<1 更灵敏；视角另有「视角加速度」 |
| 聊天框打不了字 | 模组不带输入法，用宿主的系统 IME（FCL：右菜单键盘按钮或右 Shift+Enter） |
| 摇杆自己漂 | 「轴自动识别」开着的话，连上手柄后别碰摇杆等 1.5 秒，会自动记中点并减掉 |
| 有多个手柄但没反应 / 认错手柄 | `/4y nextpad` 切；切换依据是轴数最多者优先 |
| 电量不显示 | 手柄不经系统上报电量（多数有线连接）；`SDL_GetJoystickPowerInfo` 返回未知时按原样显示 |
| `/4y perf` 没输出 | 探针默认关、且采集 180 秒后自动关；重开一次 `/4y perf`，然后进游戏或开界面呆 3 秒再看日志 |
| 在 ModMenu 里找不到 4YCtrl 的设置 | 需要 ModMenu 与本模组同时装入（`modmenu` 入口点是可选依赖）；没装也可从暂停菜单右上角进 |

## 实现说明（给想看代码的人）

| 文件 | 作用 |
| --- | --- |
| `sdl/Sdl3.java` | `DynamicLinkLoader.dlopen("libSDL3.so")` + LWJGL `JNI.invoke*` 裸调用。含代理探测与安全降级 |
| `input/GamepadManager.java` | 动作派发、径向死区、非对称去抖、轴自动校准、视角/移动换算、震动与状态提示 |
| `input/GamepadPoller.java` | **独立轮询线程**（守护线程，约 6ms/166Hz 读轴，50ms 泵事件/探掉线）。所有 SDL joystick 调用都在这里，客户端线程只读快照 |
| `input/Action.java`、`input/Button.java` | 动作 id 枚举（含 `edgeTriggered`）与 SDL 按钮名枚举 |
| `util/Perf.java` | 耗时探针（`/4y perf` 开关，默认关，采集 180 秒自动关） |
| `util/FrameGuard.java` | 帧上限守卫：识别「外部模组把帧上限压到异常低」（`UNLIMITED=260`）并跳过限帧 |
| `util/Platform.java` | 平台判定：`isAndroid` / `isFcl`（探 `POJAV_NATIVEDIR` / `FCL_NATIVEDIR`） |
| `util/Notify.java` | 动作栏（物品栏上方）一句话提示 |
| `client/ModMenuIntegration.java` | 可选 ModMenu 入口点（`modCompileOnly`），把 ModMenu 的「设置」接到本模组设置界面 |
| `client/BindingsScreen.java` | 按键绑定表：左键循环切动作、右键回退；带「⌨ 键」列 |
| `client/KeyPickerScreen.java` | 「按一个键」采集界面：把手柄按钮绑到任意 `KeyBinding` 翻译键 |
| `client/ScrollScreen.java` | 带纵向滚动条的界面基类（列表用） |
| `client/RadialMenu.java` | 按住 BACK 弹出的径向动作轮盘（右摇杆选向、松手执行） |
| `DiagCommand.java` | `/4y diag` `/4y rumble` `/4y nextpad` `/4y savepad` |
| `mixin/MouseMixin.java` | 视角注入。≤1.21.11 在 `Mouse.updateMouse()` 的 HEAD 往 `cursorDeltaX/Y` 上**叠加**（不清零，保留真实鼠标）；26.x 在 `MouseHandler.handleAccumulatedMovement()` 的 HEAD 往 `accumulatedDX/DY` 上叠加（三个 26.x 版本该类完全一致） |
| `mixin/KeyboardInputMixin.java` | 在 `KeyboardInput.tick()` 的 RETURN 覆盖成模拟量。≤1.21.11 直接写 `pressingForward` 等字段；26.x 写 `ClientInput.keyPresses`（`Input` record，潜行位叫 `shift`）+ `moveVector` |
| `mixin/GameMenuScreenMixin.java` | 在暂停菜单（26.x 叫 `PauseScreen`）右上角插入总开关 + 设置按钮（安卓上唯一必达的触屏入口） |
| `client/SettingsScreen.java` | 游戏内设置界面：滑块改死区/视角速度/移动倍率，底部显示实时轴读数 |
| `mixin/ScreenAccessor.java` | `@Invoker addDrawableChild`（26.x 是 `addRenderableWidget`）/ `@Accessor client`（26.x 是 `minecraft`），给上面那个 mixin 用 |
| `mixin/ScreenMixin.java` | 在所有界面 render 之后（抬到 z=300）画自绘光标与按键提示。26.x 注入 `extractRenderStateWithTooltipAndSubtitles` 的 TAIL，用 `pose()`（`Matrix3x2fStack`）压栈 |
| `mixin/RenderSystemMixin.java` | 帧上限守卫。≤1.21.11 把原版限帧的忙等换成真睡眠（修进世界后 10 帧锁死）；26.x 的限帧器已自带 `LockSupport.parkNanos` + 自适应补偿，本 mixin 只保留「拦掉外部模组异常低的上限」这段守卫，目标类改成 `FramerateLimiter` |
| `util/Compat.java`（mc26） | 26.1 ↔ 26.2 的 GUI 大重构兼容层：`currentScreen` / `openScreen` / `toggleHud` / `isHudHidden` / `windowFramerateLimit`。26.1 用 `Minecraft.screen` 字段 + `Options.hideGui`，26.2+ 用 `Gui.screen()` + `Gui.hud` |
| `mixin/RecipeBookWidgetAccessor.java`、`RecipeBookResultsAccessor.java` | 配方书私有字段访问（位置/按钮/页码） |
| `mixin/InGameHudMixin.java` | 在 HUD 上叠按键提示。1.21.2 起 `render` 的第二个参数从 `float tickDelta` 变成 `RenderTickCounter`，1.21.1 是分界线 |
| `client/ButtonHints.java` | 角落按键提示：按真实绑定反查，随状态与界面变化，三档详细度 |
| `config/ModConfig.java` | Gson 配置（含 `configVersion` 迁移、设备档、`keyBindings`） |
| `Keybinds.java` | F8（总开关）/ F9（调试日志） |

几个踩过的坑，记在这里省得再踩：

- **LWJGL `JNI.invokeXxx` 的调用约定**：带 `P` 的变体**最后一个 long 是函数地址**，不带 P 的（`invokeI`/`invokeZ`/`invokeV`）**第一个参数是函数地址**。把函数地址放在 `invokePP` 首位会 `SIGSEGV at pc=0x...1000`。
- **`Sint16` 返回值**：LWJGL 没有 `short f(void*,int)` 组合，得按 `int f(void*,int)` 取再 `(short)(raw & 0xFFFF)` 符号扩展 —— ABI 只保证低 16 位有效。
- **视角不能按 tick 算**：`Mouse.updateMouse()` 是**每帧**调一次，不是每 tick。按 tick 的固定 50ms 算会让高帧率下视角被放大数倍。`computeLook()` 用 `System.nanoTime()`。
- **`KeyBinding.setPressed()` 不累加 `timesPressed`**，`onKeyPressed()` 只加计数。所以点按（背包/丢弃/换副手）只调 `onKeyPressed`，长按（跳跃/攻击/使用）用 `setPressed(true)` + 一次 `onKeyPressed`。
- **`Mouse.onCursorPos()` 在无屏幕时不产生 cursorDelta**（只更新 x/y），所以视角必须写 `cursorDeltaX/Y`，不能伪造光标位置。
- **`MinecraftClient.setScreen()` 每次都会 `KeyBinding.unpressAll()`**，把我们 `setPressed` 置上的键全部清掉。
  若不处理，「按住前进时打开暂停菜单，关掉后人不走了」。`applyActions()` 里用 `wasInGame` 检测进出游戏态，
  在状态切换那一 tick 清空 `prevHeld` 让**持续动作**重新触发；同一 tick 直接 `return`，跳过边沿型动作，
  否则「按着 Y 打开背包、松手关背包」会在关闭的瞬间又打开一次。

## 开发工具（`tools/`）

`package.sh` 在收集 jar 之前会**自动跑前两个脚本**，任一 FAIL 就中止打包（`package.sh:317`、`:327`）。

| 脚本 | 作用 |
| --- | --- |
| `tools/verify_mixins.py` | 逐版本拿合并后的 MC jar，核对每个 `@Mixin` 目标类与 `@Inject` 目标方法是否真的存在。`python3 tools/verify_mixins.py [--jar ...]` |
| `tools/verify_shadow.py` | 核对每个 `@Shadow` 成员是否在**目标类自身**（不下钻父类）里。`python3 tools/verify_shadow.py [1.21.11]` / `--src` |
| `tools/headless_smoke.sh` | Xvfb + xdotool + ImageMagick 在无头环境里把 12 个版本各启一遍，截图存档。`FOURY_SELFTEST=settings\|cursor\|bindings` 可指定要验的界面，产物在 `/tmp/4y-smoke` |
| `tools/selftest_patch.py` | 临时注入 / 移除 / 查看自检脚手架。`add\|remove\|status [root\|mc26]` |

为什么 `verify_shadow.py` 必须单独存在（真实事故）：Mixin 的 `@Shadow` **只在目标类自己的成员表里查**。
曾在 `KeyboardInputMixin` 里写 `@Shadow protected Vec2 moveVector`，而 1.21.5+ / 26.x 的 `moveVector` 声明在父类（`Input` / `ClientInput`），
于是标题界面、设置界面、HUD、虚拟光标全部正常——因为 `KeyboardInput` 只在「进入世界」时才加载——一进世界立刻：

```
InvalidMixinException: @Shadow field moveVector was not located in the target class ... No refMap loaded.
```

当时 `verify_mixins.py` 只看 `@Inject` 的方法与 `@Mixin` 目标类，`@Shadow` 一个都不查，所以 12 个版本全绿却仍会崩。

## 构建

需要 **JDK 25+** 与 **Gradle ≥ 9.8**（fabric-loom 1.19.3 的硬要求）。

```bash
# 默认打全部 12 个版本：9 个 yarn 节点 + 26.1/26.2/26.3
./package.sh

# 只打指定版本（可写多个；`26` 或 `mc26` = 全部 26.x）
./package.sh 1.21.11 26.3

# 其它开关
./package.sh --clean      # 先 clean 再打
./package.sh --offline    # 离线（remapJar 的运行时库没缓存时会失败）
./package.sh --no-build   # 只把已有产物收集到 dist/
./package.sh --list       # 列出版本节点

# 单个 yarn 版本（根工程，Stonecutter 管理 9 个节点）
JAVA_HOME=/path/to/jdk25 ./gradlew :1.21.11:build

# 26.x（mc26/ 独立工程，内部也是 Stonecutter：26.1 / 26.2 / 26.3）
cd mc26 && JAVA_HOME=/path/to/jdk25 gradle build            # 三个版本全打
cd mc26 && JAVA_HOME=/path/to/jdk25 gradle :26.1:build      # 只打 26.1
```

节点可以并列：`gradle :1.20.1:build :1.20.2:build ...`。首次配置很慢（要拉 MC + 映射），之后增量很快。
26.1 / 26.2 的依赖（MC deobf、fabric-api、modmenu）首次必须联网。

> 跨版本改代码用 Stonecutter 注释：`//? if <1.20.2 { ... //?} else { ... //?}`。注意**行内做类型替换会编译失败**（碎片残留），必须整段闭合把两份都写出来。
>
> `mc26/` 里 `versions/current` 指向的那个节点**直接编译 `src/` 原文**（不走生成目录），所以 `src/` 必须写成该版本可编译的形态 —— 当前固定指向 `26.3`。

## 许可

MIT。
