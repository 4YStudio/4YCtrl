package com.fouryctrl.mixin;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.input.GamepadManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移动注入点（模拟量）+ 潜行 / 疾跑接管。
 *
 * <p>为什么必须打在 {@code tick} 的 RETURN：{@code KeyboardInput.tick(boolean,float)}
 * 里 {@code movementForward = getMovementMultiplier(pressingForward, pressingBack)}，
 * 那个方法只能返回 -1 / 0 / +1 —— 也就是说键盘语义天生没有中间值。
 * 想拿到摇杆的模拟量，只能等它算完再覆盖。</p>
 *
 * <p><b>三个版本段（javap 实测，别凭记忆改）</b></p>
 * <ul>
 *   <li>1.20.1–1.21.1：{@code Input} 有 {@code movementForward/movementSideways} 与
 *       {@code pressingForward/.../sneaking} 一堆 boolean，{@code tick(boolean,float)}
 *       自带降速参数。写字段即可。</li>
 *   <li>1.21.4：{@code tick()} 无参，先造一个 {@code PlayerInput}(7 个 boolean) 再算
 *       {@code movementForward/movementSideways}；潜行/跳跃由
 *       {@code ClientPlayerEntity.tickMovementInput} 从 {@code playerInput} 读，
 *       所以潜行必须写 {@code playerInput.sneak}。</li>
 *   <li>1.21.5+：{@code movementForward/movementSideways} 字段被删，
 *       改成一个 {@code movementVector(Vec2f)}，{@code getMovementInput()} 直接返回它；
 *       {@code ClientPlayerEntity.tickMovementInput} 读的也是
 *       {@code input.getMovementInput()}。只写 playerInput 不够 ——
 *       角色会站着不动（1.21.8 首轮编译通过但摇杆失效就是这样）。</li>
 * </ul>
 *
 * <p><b>潜行 / 疾跑为什么走这里而不是 KeyBinding</b>：{@code GameOptions.sneakKey}
 * 与 {@code sprintKey} 都是 {@code StickyKeyBinding}，玩家在无障碍选项里打开
 * 「切换潜行 / 切换疾跑」时，它们的 {@code setPressed(false)} 会被完全忽略
 * （只有 true 会翻转状态），于是出现「松开按键却站不起来」「再按又立刻蹲下」。
 * 直接写输入状态完全绕开这条路径，且仍会被 {@code tickMovement} 与
 * {@code sendMovementPackets} 正常消费。</p>
 * <p><b>为什么这里没有 {@code @Shadow} 字段</b>：{@code movementVector} 从 1.21.5 起
 * 搬到了<b>父类</b> {@code Input}，{@code KeyboardInput} 自己一个字段都不声明。Mixin 的
 * {@code @Shadow} 只在目标类自己的字段表里查，<b>不会</b>沿继承链往上找，所以
 * {@code @Shadow protected Vec2f movementVector;} 在运行期会直接炸：
 * {@code InvalidMixinException: @Shadow field movementVector was not located in the
 * target class net.minecraft.client.input.KeyboardInput. No refMap loaded.}
 * 而 {@code KeyboardInput} 只在进入世界后才加载 —— 于是「标题界面 / 设置界面 / HUD
 * 一切正常，一进世界立刻断线」。现在改成经 {@link InputAccessor} 读写父类字段。</p>
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    //? if <1.21.4 {
    /*@Inject(method = "tick", at = @At("RETURN"))
    private void fouryctrl$applyStick(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        GamepadManager gamepad = FourYCtrl.gamepad();
        if (gamepad == null || !gamepad.state().connected) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || !gamepad.isDriving(client)) {
            return;
        }
        KeyboardInput self = (KeyboardInput) (Object) this;

        float forward = gamepad.moveForward();
        float sideways = gamepad.moveSideways();
        if (forward != 0f || sideways != 0f) {
            if (slowDown) {
                forward *= slowDownFactor;
                sideways *= slowDownFactor;
            }
            self.movementForward = forward;
            // 注意 vanilla 约定：movementSideways 是 +1 = 向左（字节码里是
            // getMovementMultiplier(pressingLeft, pressingRight)），与摇杆的「+x = 向右」
            // 正好相反；不取反的话左推摇杆人就往右走。
            self.movementSideways = -sideways;
            // 让「向前跑」「前进相关判定」也认为在前进
            self.pressingForward = forward > 1.0e-4f;
            self.pressingBack = forward < -1.0e-4f;
            self.pressingLeft = sideways < -1.0e-4f;
            self.pressingRight = sideways > 1.0e-4f;
        }

        // ---- 潜行 ----
        if (gamepad.wantSneak()) {
            self.sneaking = true;
            gamepad.setSneakForced(true);
        } else if (gamepad.sneakForced()) {
            // 只收回「我们按下去的」那一次，键盘的潜行状态不碰。
            self.sneaking = false;
            gamepad.setSneakForced(false);
        }

        // ---- 疾跑 ----
        // 必须在 pressingForward 已被摇杆覆盖之后判断，否则「推摇杆向前 + 开疾跑」时
        // 会误以为没有前进输入。
        gamepad.applySprintState(client.player, self.pressingForward);
    }
    *///?} else {
    @Inject(method = "tick", at = @At("RETURN"))
    private void fouryctrl$applyStick(CallbackInfo ci) {
        GamepadManager gamepad = FourYCtrl.gamepad();
        if (gamepad == null || !gamepad.state().connected) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || !gamepad.isDriving(client)) {
            return;
        }
        KeyboardInput self = (KeyboardInput) (Object) this;

        float forward = gamepad.moveForward();
        float sideways = gamepad.moveSideways();
        if (forward != 0f || sideways != 0f) {
            // 摇杆是模拟量，这里不归一化（对角不加速由 tickMovement 的
            // applyMovementSpeedFactors 负责），与旧版覆盖 movementForward 的语义一致。
            //? if <1.21.5 {
            /*self.movementForward = forward;
            self.movementSideways = -sideways;
            *///?} else {
            ((InputAccessor) this).fouryctrl$setMovementVector(new net.minecraft.util.math.Vec2f(-sideways, forward));
            //?}
        }

        // playerInput 是 1.21.4 起唯一的「按键状态源」：ClientPlayerEntity 从这里读
        // jump/sneak/backward/sprint。摇杆的方向布尔也要写进去，
        // 否则「推摇杆前进 + 疾跑」这类依赖 playerInput.forward() 的判定会失效。
        net.minecraft.util.PlayerInput prev = self.playerInput;
        boolean wantSneak = gamepad.wantSneak() || (!gamepad.sneakForced() && prev.sneak());
        if (gamepad.wantSneak()) {
            gamepad.setSneakForced(true);
        } else if (gamepad.sneakForced()) {
            // 只收回「我们按下去的」那一次，键盘的潜行状态不碰。
            gamepad.setSneakForced(false);
            wantSneak = false;
        }
        boolean hasStick = forward != 0f || sideways != 0f;
        self.playerInput = new net.minecraft.util.PlayerInput(
                hasStick ? forward > 1.0e-4f : prev.forward(),
                hasStick ? forward < -1.0e-4f : prev.backward(),
                hasStick ? sideways < -1.0e-4f : prev.left(),
                hasStick ? sideways > 1.0e-4f : prev.right(),
                prev.jump(),
                wantSneak,
                prev.sprint());

        // ---- 疾跑 ----
        // 必须在前进状态已被摇杆覆盖之后判断，否则「推摇杆向前 + 开疾跑」时
        // 会误以为没有前进输入。
        gamepad.applySprintState(client.player, forward > 1.0e-4f);
    }
    //?}
}
