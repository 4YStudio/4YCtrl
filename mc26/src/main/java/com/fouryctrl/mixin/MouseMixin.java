package com.fouryctrl.mixin;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.input.GamepadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 视角注入点（26.3 版）。
 *
 * <h2>26.3 变了什么</h2>
 * <p>1.20.1 的入口是 {@code Mouse.updateMouse()}，它在内部把
 * {@code cursorDeltaX/Y} 清零后直接改玩家朝向。26.3 拆成了两步：
 * {@code MouseHandler.onMove(...)} 只负责<b>累加</b>
 * {@code accumulatedDX/accumulatedDY}（鼠标被抓取时累加原始 dx，否则累加
 * 「新位置 - xpos」），真正的转向在 {@code handleAccumulatedMovement()} 里由
 * {@code turnPlayer(dt)} 用 {@code accumulatedDX * 灵敏度曲线} 完成，之后把两个
 * 累加器清零。</p>
 *
 * <p>所以注入点选 {@code handleAccumulatedMovement} 的 HEAD，往
 * {@code accumulatedDX/accumulatedDY} 上<b>叠加</b>摇杆位移。这样：</p>
 * <ul>
 *   <li>MC 自己会套用灵敏度三次方曲线、平滑相机、望远镜降速、反转 Y ——
 *       手感与 PC 上原生手柄一致，这是 FCL 自带映射（把摇杆量化成 8 个方向）
 *       做不到的；</li>
 *   <li>叠加而不是覆盖，真实鼠标位移不会被抹掉；</li>
 *   <li>HEAD 写入时原值还没被清零，本帧立刻生效，不慢一帧。</li>
 * </ul>
 *
 * <p>顺带在 HEAD 先调 {@code Perf.frame()}（帧计数）与 {@code beginFrame()}
 * （让 computeLook / frameCursor 共享同一次读轴 JNI）。</p>
 */
@Mixin(MouseHandler.class)
public class MouseMixin {
    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void fouryctrl$injectLook(CallbackInfo ci) {
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
        Minecraft client = Minecraft.getInstance();
        long p = com.fouryctrl.util.Perf.begin();
        gamepad.computeLook(client);
        com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.LOOK, p);
        // 叠加而不是覆盖：真实鼠标的位移不能被我们抹掉。
        accumulatedDX += gamepad.lookX();
        accumulatedDY += gamepad.lookY();
    }

    /**
     * 逐帧推进 GUI 光标位置。
     *
     * <p>放在 {@code handleAccumulatedMovement} 的 RETURN：它在每帧渲染前、
     * {@code Gui.extractRenderState} 读 {@code MouseHandler.getScaledXPos/YPos}
     * 算界面 hover 坐标之前执行，所以本帧推进的位置本帧就能生效，不会慢一帧。
     * 放在 tick 里（20Hz）则会一顿一顿。</p>
     */
    @Inject(method = "handleAccumulatedMovement", at = @At("RETURN"))
    private void fouryctrl$frameCursor(CallbackInfo ci) {
        GamepadManager gamepad = FourYCtrl.gamepad();
        if (gamepad == null) {
            return;
        }
        long p = com.fouryctrl.util.Perf.begin();
        gamepad.frameCursor(Minecraft.getInstance());
        com.fouryctrl.util.Perf.end(com.fouryctrl.util.Perf.CURSOR, p);
    }
}
