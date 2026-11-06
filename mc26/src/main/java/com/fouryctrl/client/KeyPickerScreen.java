package com.fouryctrl.client;

import com.fouryctrl.config.ModConfig;
import com.fouryctrl.input.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * 「按一个键」采集界面：给某个手柄按钮绑一个键盘按键（或鼠标键）。
 *
 * <p>为什么要有这一层：本模组自带的动作只有十来个，玩家想用手柄触发<b>别的模组</b>
 * 的功能（小地图、回放、一键整理……）没有别的路。MC 里所有这类功能都挂在
 * {@code KeyMapping} 上，而 {@code KeyMapping.click(InputConstants.Key)} 是按
 * {@link InputConstants.Key} 查表的静态方法 —— 只要我们能构造出同一个 Key 对象，
 * 就等于替玩家按下了那个键，不需要任何反射，也就能驱动别的模组。</p>
 *
 * <p>这里只负责采集键码并存成 {@code key.keyboard.x} / {@code key.mouse.left}
 * 这类翻译键（{@code GamepadManager} 每 tick 用 {@code InputConstants.fromTranslationKey}
 * 反查回来）。Esc 取消，鼠标左右中键会被当成 {@code key.mouse.*} 收下。</p>
 */
public class KeyPickerScreen extends Screen {
    private static final int COLOR_HINT = 0xFFA8A8A8;

    private final Screen parent;
    private final ModConfig cfg;
    private final String buttonId;

    public KeyPickerScreen(Screen parent, ModConfig cfg, String buttonId) {
        super(Component.translatable("fouryctrl.keypicker.title",
                Component.translatable("fouryctrl.button." + buttonId)));
        this.parent = parent;
        this.cfg = cfg;
        this.buttonId = buttonId;
    }

    @Override
    protected void init() {
        int w = 150;
        int x = this.width / 2 - w - 3;
        int y = this.height / 2 + 24;
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("fouryctrl.keypicker.clear"), b -> {
            if (cfg != null && cfg.keyBindings != null) {
                cfg.keyBindings.remove(buttonId);
                cfg.save();
            }
            back();
        }).bounds(x, y, w, 20).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.cancel"), b -> back())
                .bounds(x + w + 6, y, w, 20).build());
    }

    private void back() {
        if (parent instanceof BindingsScreen bs) {
            bs.refreshAfterKeyChange();
        }
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            back();
            return true;
        }
        // 直接存翻译键而不是裸键码：配置文件可读（key.keyboard.f），而且和
        // KeyMapping 的 getTranslatedKeyMessage 同一套命名，反查一定成功。
        // InputConstants.getKey(KeyEvent) 三个 26.x 版本都在，且内部按版本自己选
        // KEYSYM（26.1/26.2）还是 KEYBOARD（26.3）——比直接写 Type.KEYBOARD 更稳。
        InputConstants.Key key = InputConstants.getKey(event);
        return apply(key);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 先让底部按钮吃到点击，否则点「取消」会被当成「把鼠标左键绑上去」。
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(event.button());
        return apply(key);
    }

    private boolean apply(InputConstants.Key key) {
        if (key == null || key == InputConstants.UNKNOWN) {
            return false;
        }
        if (cfg != null && cfg.keyBindings != null) {
            cfg.keyBindings.put(buttonId, key.getName());
            cfg.save();
        }
        back();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 同 BindingsScreen：背景/模糊由基类管线负责，手动调 extractBackground 会触发
        // "Can only blur once per frame" 崩溃。
        context.centeredText(this.font, this.title, this.width / 2,
                this.height / 2 - 40, 0xFFFFFFFF);
        String hint = Component.translatable("fouryctrl.keypicker.hint").getString();
        context.centeredText(this.font, hint, this.width / 2,
                this.height / 2 - 20, COLOR_HINT);
        String now = Component.translatable("fouryctrl.keypicker.current",
                Component.literal(displayName(currentKey()))).getString();
        context.centeredText(this.font, now, this.width / 2,
                this.height / 2, 0xFFFFFF88);
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private InputConstants.Key currentKey() {
        if (cfg == null || cfg.keyBindings == null) {
            return null;
        }
        String id = cfg.keyBindings.get(buttonId);
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return InputConstants.getKey(id);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean isPauseScreen() {
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
    static String displayName(InputConstants.Key key) {
        if (key == null || key == InputConstants.UNKNOWN) {
            return "-";
        }
        String raw = key.getName();
        String localized;
        try {
            localized = key.getDisplayName().getString();
        } catch (Throwable t) {
            localized = null;
        }
        if (localized != null && !localized.isEmpty() && !localized.equals(raw)) {
            return localized;
        }
        int code = key.getValue();
        if (key.getType() == InputConstants.Type.MOUSE) {
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
            return displayName(InputConstants.getKey(id));
        } catch (Throwable t) {
            return id;
        }
    }
}
