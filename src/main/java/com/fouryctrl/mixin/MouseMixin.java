package com.fouryctrl.mixin;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.input.GamepadManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 视角 / GUI 光标注入点。
 *
 * <h2>为什么按版本换注入目标</h2>
 * <p>1.20.6 起 Minecraft 把鼠标处理拆成了两层：</p>
 * <ul>
 *   <li>{@code Mouse.tick()}（无参）—— <b>每帧渲染前无条件调一次</b>
 *       （{@code MinecraftClient.render} 的 {@code "mouse"} 段）。它负责把
 *       {@code cursorDeltaX/Y} 喂给界面的 {@code mouseMoved/mouseDragged}，
 *       再在末尾把两个 delta 清零。</li>
 *   <li>{@code Mouse.updateMouse(double)} —— 只在
 *       <b>{@code isMouseGrabbed() && player != null}</b>（也就是「游戏内、没开界面」）
 *       时才会被 {@code tick()} 调到，负责真正的转向。</li>
 * </ul>
 * <p>之前的实现把两个注入都挂在 {@code updateMouse} 上。视角没问题（本来只在游戏内），
 * 但 {@code frameCursor} 也跟着被门控了 —— <b>一打开界面 {@code updateMouse} 就不再被调用，
 * 虚拟光标因此完全不动</b>，这就是 1.21.11（以及所有 1.20.6+）「摇杆控虚拟鼠标失效」的根因。
 * 1.20.1 ~ 1.20.4 之所以正常，是因为那一代的 {@code updateMouse()} 是每帧无条件调用、
 * 抓取判断在方法体内部，注入永远会执行。</p>
 *
 * <p>所以：1.20.6+ 挂 {@code tick()}（每帧必过），1.20.1 ~ 1.20.4 仍是 {@code updateMouse()}。</p>
 *
 * <h2>为什么写 cursorDelta 而不是直接改玩家朝向</h2>
 * <ul>
 *   <li>写 cursorDelta 之后，Minecraft 自己会套用灵敏度三次方曲线、平滑相机、
 *       望远镜降速、反转 Y —— 手感与 PC 上原生手柄一致，
 *       这正是 FCL 自带映射（把摇杆量化成 8 个方向）做不到的。</li>
 *   <li>叠加而不是覆盖：真实鼠标的位移不会被抹掉。</li>
 * </ul>
 */
@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow
    private double cursorDeltaX;

    @Shadow
    private double cursorDeltaY;

    //? if <1.20.6 {
    /*// 1.20.1 ~ 1.20.4：updateMouse() 每帧无条件调用，抓取判断在方法体内部。
    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void fouryctrl$injectLook(CallbackInfo ci) {
        fouryctrl$onFrameHead();
    }

    @Inject(method = "updateMouse", at = @At("RETURN"))
    private void fouryctrl$frameCursor(CallbackInfo ci) {
        fouryctrl$onFrameTail();
    }
    *///?} else {
    // 1.20.6+：updateMouse(double) 只在鼠标被抓取时才会被调到，
    // 界面里完全不执行 —— 必须挂到每帧必过的 tick()。
    @Inject(method = "tick", at = @At("HEAD"))
    private void fouryctrl$injectLook(CallbackInfo ci) {
        fouryctrl$onFrameHead();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fouryctrl$frameCursor(CallbackInfo ci) {
        fouryctrl$onFrameTail();
    }
    //?}

    /**
     * 每帧开始：计数、共享读轴、算视角并叠加到 {@code cursorDeltaX/Y}。
     *
     * <p>界面里 {@link GamepadManager#computeLook} 会因
     * {@code isDriving()==false} 直接返回 0，所以这里叠加的 0 不会触发
     * 界面的 {@code mouseMoved/mouseDragged}。</p>
     */
    private void fouryctrl$onFrameHead() {
        GamepadManager gamepad = FourYCtrl.gamepad();
        com.fouryctrl.util.Perf.frame();
        if (gamepad == null) {
            return;
        }
        // 一帧内 computeLook / frameCursor 会先后读同一对右摇杆轴，
        // 由这里开一帧，让它们共享一次 JNI 读取结果。
        gamepad.beginFrame();
        if (!gamepad.state().connected) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        long p = com.fouryctrl.util.Perf.begin();
        gamepad.computeLook(client);
        com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.LOOK, p);
        // 叠加而不是覆盖：真实鼠标的位移不能被我们抹掉。
        cursorDeltaX += gamepad.lookX();
        cursorDeltaY += gamepad.lookY();
    }

    /** 每帧结束：推进 GUI 光标位置，并在本帧渲染前把鼠标坐标同步出去。 */
    private void fouryctrl$onFrameTail() {
        GamepadManager gamepad = FourYCtrl.gamepad();
        if (gamepad == null) {
            return;
        }
        long p = com.fouryctrl.util.Perf.begin();
        gamepad.frameCursor(MinecraftClient.getInstance());
        com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.CURSOR, p);
    }
}
