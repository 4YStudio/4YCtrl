package com.fouryctrl.client;

import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.Button;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import com.fouryctrl.util.Compat;

import java.util.Locale;

/**
 * 「按一个键」采集界面：给某个手柄按钮绑一个键盘按键（或鼠标键）。
 *
 * <p>为什么要有这一层：本模组自带的动作只有十来个，玩家想用手柄触发<b>别的模组</b>
 * 的功能（小地图、回放、一键整理……）没有别的路。MC 里所有这类功能都挂在
 * {@code KeyBinding} 上，而 {@code KeyBinding.onKeyPressed(InputUtil.Key)} 是按
 * {@link InputUtil.Key} 查表的静态方法 —— 只要我们能构造出同一个 Key 对象，
 * 就等于替玩家按下了那个键，不需要任何反射，也就能驱动别的模组。</p>
 *
 * <p>这里只负责采集键码并存成 {@code key.keyboard.x} / {@code key.mouse.left}
 * 这类翻译键（{@code GamepadManager} 每 tick 用 {@code InputUtil.fromTranslationKey}
 * 反查回来）。Esc 取消，鼠标左右中键会被当成 {@code key.mouse.*} 收下。</p>
 */
public class KeyPickerScreen extends Screen {
    private static final int COLOR_HINT = 0xFFA8A8A8;

    private final Screen parent;
    private final ModConfig cfg;
    private final String buttonId;

    public KeyPickerScreen(Screen parent, ModConfig cfg, String buttonId) {
        super(Text.translatable("fouryctrl.keypicker.title",
                Text.translatable("fouryctrl.button." + buttonId)));
        this.parent = parent;
        this.cfg = cfg;
        this.buttonId = buttonId;
    }

    @Override
    protected void init() {
        int w = 150;
        int x = this.width / 2 - w - 3;
        int y = this.height / 2 + 24;
        addDrawableChild(ButtonWidget.builder(Text.translatable("fouryctrl.keypicker.clear"), b -> {
            if (cfg != null && cfg.keyBindings != null) {
                cfg.keyBindings.remove(buttonId);
                cfg.save();
            }
            back();
        }).dimensions(x, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> back())
                .dimensions(x + w + 6, y, w, 20).build());
    }

    private void back() {
        if (parent instanceof BindingsScreen bs) {
            bs.refreshAfterKeyChange();
        }
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    //? if <1.21.11 {
    /*@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return keyPressed0(keyCode);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 先让底部按钮吃到点击，否则点「取消」会被当成「把鼠标左键绑上去」。
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return apply(InputUtil.Type.MOUSE.createFromCode(button));
    }
    *///?} else {
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return keyPressed0(input.key());
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        // 先让底部按钮吃到点击，否则点「取消」会被当成「把鼠标左键绑上去」。
        if (super.mouseClicked(click, doubled)) {
            return true;
        }
        return apply(InputUtil.Type.MOUSE.createFromCode(click.button()));
    }
    //?}

    private boolean keyPressed0(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        // 直接存翻译键而不是裸键码：配置文件可读（key.keyboard.f），而且和
        // KeyBinding 的 boundKeyTranslationKey 同一套命名，反查一定成功。
        InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(keyCode);
        return apply(key);
    }

    private boolean apply(InputUtil.Key key) {
        if (key == null || key == InputUtil.UNKNOWN_KEY) {
            return false;
        }
        if (cfg != null && cfg.keyBindings != null) {
            cfg.keyBindings.put(buttonId, key.getTranslationKey());
            cfg.save();
        }
        back();
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        Compat.renderBackground(this, context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2,
                this.height / 2 - 40, 0xFFFFFFFF);
        String hint = Text.translatable("fouryctrl.keypicker.hint").getString();
        context.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2,
                this.height / 2 - 20, COLOR_HINT);
        String now = Text.translatable("fouryctrl.keypicker.current",
                Text.literal(displayName(currentKey()))).getString();
        context.drawCenteredTextWithShadow(this.textRenderer, now, this.width / 2,
                this.height / 2, 0xFFFFFF88);
        super.render(context, mouseX, mouseY, delta);
    }

    private InputUtil.Key currentKey() {
        if (cfg == null || cfg.keyBindings == null) {
            return null;
        }
        String id = cfg.keyBindings.get(buttonId);
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return InputUtil.fromTranslationKey(id);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    // ------------------------------------------------------------------ 显示名

    /**
     * 键的可读名字。
     *
     * <p>安卓上 {@code GLFW.glfwGetKeyName} 经常返回 null，所以字母键的官方
     * localizedText 会退化成未翻译的 {@code key.keyboard.f}。这里在「显示名等于
     * 翻译键」时自己按码点造一个可读字符，避免界面上出现一堆 key.keyboard.xx。</p>
     */
    static String displayName(InputUtil.Key key) {
        if (key == null || key == InputUtil.UNKNOWN_KEY) {
            return "-";
        }
        String raw = key.getTranslationKey();
        String localized;
        try {
            localized = key.getLocalizedText().getString();
        } catch (Throwable t) {
            localized = null;
        }
        if (localized != null && !localized.isEmpty() && !localized.equals(raw)) {
            return localized;
        }
        int code = key.getCode();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return "Mouse" + (code + 1);
        }
        // GLFW 键码对字母/数字就是 ASCII 大写码点。
        if (code >= 32 && code < 127) {
            return String.valueOf((char) Character.toUpperCase(code));
        }
        if (raw == null) {
            return "?";
        }
        int dot = raw.lastIndexOf('.');
        return (dot >= 0 ? raw.substring(dot + 1) : raw).toUpperCase(Locale.ROOT);
    }

    /** 供绑定界面取用：配置里那条键盘绑定现在叫什么。 */
    static String displayNameOf(ModConfig cfg, Button button) {
        if (cfg == null || cfg.keyBindings == null || button == null) {
            return "-";
        }
        String id = cfg.keyBindings.get(button.id);
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return displayName(InputUtil.fromTranslationKey(id));
        } catch (Throwable t) {
            return id;
        }
    }
}
