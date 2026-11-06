package com.fouryctrl.mixin;

import com.fouryctrl.FourYCtrl;
import com.fouryctrl.input.GamepadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移动注入点（模拟量）+ 潜行 / 疾跑接管（26.3 版）。
 *
 * <h2>26.3 的输入模型变了</h2>
 * <p>1.20.1 的 {@code KeyboardInput} 直接持有 {@code pressingForward} 等布尔字段与
 * {@code movementForward/movementSideways} 两个 float，我们在 tick 的 RETURN 里覆盖它们。</p>
 *
 * <p>26.3 把这些搬进了父类 {@code ClientInput}：{@code public Input keyPresses}
 * （record，7 个 boolean，<b>潜行位叫 {@code shift}</b>）与 {@code protected Vec2 moveVector}。
 * {@code KeyboardInput.tick()} 只负责「读 KeyMapping → 造 Input → 造 Vec2」，
 * 真正的消费在 {@code LocalPlayer.applyInput()}：{@code xxa = moveVector.x}、
 * {@code zza = moveVector.y}、{@code jumping = keyPresses.jump()}。</p>
 *
 * <p>所以这里改成：tick 的 RETURN 处<b>重建一个 Input + 一个 Vec2</b> 写回原来的两个字段。
 * 好处是 26.3 的连带逻辑自动生效：</p>
 * <ul>
 *   <li>{@code LocalPlayer.isShiftKeyDown()} = {@code keyPresses.shift()}，
 *       潜行姿态与「潜行降速」（{@code isMovingSlowly()} →
 *       {@code Attributes.SNEAKING_SPEED}）全部由原版自己乘，<b>不需要我们再乘一次</b>；</li>
 *   <li>{@code ClientInput.hasForwardImpulse()} = {@code moveVector.y > 1e-5f}，
 *       摇杆推前会让「能不能起跑」判定自然成立。</li>
 * </ul>
 *
 * <p>注意 {@code Input} 是 record（不可变），只能整体替换；所以先把原值读出来，
 * 把摇杆与开关的值并/覆盖进去，再造新的。</p>
 *
 * <p><b>潜行 / 疾跑为什么走这里而不是 KeyMapping</b>：{@code Options.keyShift}
 * 与 {@code keySprint} 是 {@code ToggleKeyMapping}，玩家在无障碍选项里打开
 * 「切换潜行 / 切换疾跑」时，它们的 {@code setDown(false)} 会被完全忽略
 * （只有 true 会翻转状态），于是出现「松开按键却站不起来」「再按又立刻蹲下」。
 * 直接写 Input 与 {@code setSprinting} 完全绕开这条路径。</p>
 * <p><b>为什么这里没有 {@code @Shadow} 字段</b>：{@code moveVector} 是父类
 * {@code ClientInput} 的字段，{@code KeyboardInput} 自己一个字段都没有。Mixin 的
 * {@code @Shadow} 只在目标类自己的字段表里查，<b>不会</b>沿继承链往上找，所以
 * {@code @Shadow protected Vec2 moveVector;} 在运行期会直接炸：
 * {@code InvalidMixinException: @Shadow field moveVector was not located in the target
 * class net.minecraft.client.player.KeyboardInput. No refMap loaded.}
 * 这个类只在进入世界后才加载，于是「标题界面一切正常，一进世界就断线」。
 * 改成经 {@link InputAccessor} 读写父类字段。</p>
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void fouryctrl$applyStick(CallbackInfo ci) {
        GamepadManager gamepad = FourYCtrl.gamepad();
        if (gamepad == null || !gamepad.state().connected) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || !gamepad.isDriving(client)) {
            return;
        }
        KeyboardInput self = (KeyboardInput) (Object) this;
        Input in = self.keyPresses;
        if (in == null) {
            in = Input.EMPTY;
        }

        boolean forward = in.forward();
        boolean backward = in.backward();
        boolean left = in.left();
        boolean right = in.right();
        boolean jump = in.jump();
        boolean shift = in.shift();
        boolean sprint = in.sprint();

        // ---- 摇杆移动（模拟量）----
        float fwd = gamepad.moveForward();
        float side = gamepad.moveSideways();
        if (fwd != 0.0f || side != 0.0f) {
            // vanilla 约定：moveVector.x = calculateImpulse(left, right)，即 +x = 向左；
            // 摇杆的「+x = 向右」正好相反，不取反左推摇杆人就往右走。
            // 也不做 normalize()：归一化会把小幅度推杆抹成满速，模拟量就没了。
            ((InputAccessor) this).fouryctrl$setMoveVector(new Vec2(-side, fwd));
            // 同步布尔位，让「是否在前进」这类第三方判定也看到摇杆。
            forward = fwd > 1.0e-4f;
            backward = fwd < -1.0e-4f;
            left = side < -1.0e-4f;
            right = side > 1.0e-4f;
        }

        // ---- 潜行 ----
        if (gamepad.wantSneak()) {
            shift = true;
            gamepad.setSneakForced(true);
        } else if (gamepad.sneakForced()) {
            // 只收回「我们按下去的」那一次，键盘的潜行状态不碰。
            shift = false;
            gamepad.setSneakForced(false);
        }

        // ---- 疾跑 ----
        sprint = sprint || gamepad.sprintActive();

        self.keyPresses = new Input(forward, backward, left, right, jump, shift, sprint);

        // 必须在 moveVector 已被摇杆覆盖之后判断，否则「推摇杆向前 + 开疾跑」时
        // 会误以为没有前进输入。
        Vec2 mv = ((InputAccessor) this).fouryctrl$getMoveVector();
        gamepad.applySprintState(client.player, mv != null && mv.y > 1.0e-5f);
    }
}
