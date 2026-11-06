package com.fouryctrl.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link ClientInput#moveVector} 的读写访问器（26.1 / 26.2 / 26.3）。
 *
 * <h2>为什么必须有这个类</h2>
 * <p>{@code moveVector} 是 <b>父类 {@code ClientInput} 的字段</b>，
 * {@code KeyboardInput} 自己一个字段都没声明（26.1/26.2/26.3 javap 实测，三版一致）：
 * <pre>
 * public class KeyboardInput extends ClientInput {
 *     private final Options options;
 *     public void tick();
 * }
 * public class ClientInput {
 *     public Input keyPresses;
 *     protected Vec2 moveVector;   // ← 在父类
 *     public Vec2 getMoveVector();
 *     public boolean hasForwardImpulse();
 *     public void makeJump();
 * }
 * </pre>
 *
 * <p>Mixin 的 {@code @Shadow} <b>只在目标类自己的字段表里查找</b>，<b>不会</b>沿继承链
 * 往上找。于是 {@code KeyboardInputMixin} 里的
 * {@code @Shadow protected Vec2 moveVector;} 在运行期直接炸：</p>
 * <pre>
 * java.lang.RuntimeException: Mixin transformation of
 *   net.minecraft.client.player.KeyboardInput failed
 * Caused by: org.spongepowered.asm.mixin.throwables.InvalidMixinException:
 *   &#64;Shadow field moveVector was not located in the target class
 *   net.minecraft.client.player.KeyboardInput. No refMap loaded.
 * </pre>
 *
 * <p>而且这个类只在<b>进入世界之后</b>才会被加载（要造本地玩家才会 new
 * {@code KeyboardInput}），所以标题界面 / 设置界面一切正常，一进世界就崩 ——
 * 表现为「网络协议错误」或单人世界直接被踢回主菜单。</p>
 *
 * <p>修法：给父类单独开一个 accessor mixin。{@code KeyboardInput} 实例本身就是
 * {@code ClientInput} 子类，强转即可拿到同一份字段。</p>
 *
 * <p><b>类名为什么要和根工程的 {@code InputAccessor} 完全一致</b>：
 * {@code fouryctrl.mixins.json} 是根工程与 mc26 工程<b>共用的符号链接</b>
 * （{@code mc26/src/main/resources/fouryctrl.mixins.json -> ../../../../src/main/resources/fouryctrl.mixins.json}），
 * 表里只能写一个名字。所以两边各提供一份同名类、各注入各的目标类：
 * 根工程注入 {@code net.minecraft.client.input.Input}（1.21.5+），
 * 本文件注入 26.x 改名后的 {@code net.minecraft.client.player.ClientInput}。</p>
 */
@Mixin(ClientInput.class)
public interface InputAccessor {
    @Accessor("moveVector")
    Vec2 fouryctrl$getMoveVector();

    @Accessor("moveVector")
    void fouryctrl$setMoveVector(Vec2 moveVector);
}
