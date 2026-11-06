package com.fouryctrl.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code movementVector} 的读写访问器（<b>1.21.5 及以后才有实际成员</b>）。
 *
 * <h2>为什么必须有这个类</h2>
 * <p>1.21.5 起 {@code movementVector} 从 {@code KeyboardInput} 搬到了<b>父类</b>
 * {@code net.minecraft.client.input.Input}，而 {@code KeyboardInput} 自己一个字段都不声明：
 * <pre>
 * public class KeyboardInput extends Input {
 *     private final GameOptions settings;
 *     public void tick();
 * }
 * public class Input {
 *     public PlayerInput playerInput;
 *     protected Vec2f movementVector;   // ← 在父类（1.21.5+）
 *     public float movementSideways;    // ← 1.21.4 还是这两个 float
 *     public float movementForward;
 * }
 * </pre>
 *
 * <p>Mixin 的 {@code @Shadow} <b>只在目标类自己的字段表里查</b>，<b>不会</b>沿继承链
 * 往上找。所以在 {@code KeyboardInputMixin} 里写
 * {@code @Shadow protected Vec2f movementVector;} 会在运行期直接炸：</p>
 * <pre>
 * Caused by: org.spongepowered.asm.mixin.throwables.InvalidMixinException:
 *   &#64;Shadow field movementVector was not located in the target class
 *   net.minecraft.client.input.KeyboardInput. No refMap loaded.
 * </pre>
 *
 * <p>而且 {@code KeyboardInput} 只在<b>进入世界之后</b>才会被加载（要造本地玩家才
 * new 它），所以标题界面、设置界面、HUD 全部正常，一进世界立刻断线/崩溃。</p>
 *
 * <p><b>为什么保留这个类名、而不是按版本增删 mixin 表条目</b>：
 * {@code fouryctrl.mixins.json} 是<b>根工程与 mc26 工程共用的符号链接</b>
 * （{@code mc26/src/main/resources/fouryctrl.mixins.json -> ../../../../src/main/resources/fouryctrl.mixins.json}），
 * 任何一方专属的类名写进表里，另一方加载时就是 {@code ClassNotFoundException}。
 * 所以这里固定用同一个类名，由两个工程各自的源码树提供不同实现：</p>
 * <ul>
 *   <li>根工程（1.20.1–1.21.11）→ 本文件，{@code @Mixin(Input.class)}；</li>
 *   <li>mc26 工程（26.1–26.3）→ {@code mc26/src/main/java/com/fouryctrl/mixin/InputAccessor.java}，
 *       {@code @Mixin(ClientInput.class)}（26.x 把 Input 改名成了 ClientInput）。</li>
 * </ul>
 *
 * <p><b>版本门控</b>：1.21.5 之前没有 {@code movementVector} 字段，所以两个
 * {@code @Accessor} 方法被包在 {@code //? if >=1.21.5} 里 —— 此时本类退化成空接口
 * （mixin 允许零成员，目标类只是多实现一个标记接口），1.20.1–1.21.4 照常构建，
 * 那条分支的 {@code KeyboardInputMixin} 也根本不强转成这个接口。</p>
 */
@Mixin(Input.class)
public interface InputAccessor {
    //? if >=1.21.5 {
    @Accessor("movementVector")
    Vec2f fouryctrl$getMovementVector();

    @Accessor("movementVector")
    void fouryctrl$setMovementVector(Vec2f movementVector);
    //?}
}
