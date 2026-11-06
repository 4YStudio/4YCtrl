package com.fouryctrl.util;

/** 运行环境判定。判据与 Controlify 保持一致，避免误判。 */
public final class Platform {
    private Platform() {
    }

    private static Boolean android;

    public static boolean isAndroid() {
        if (android == null) {
            String vmName = System.getProperty("java.vm.name", "");
            android = "dalvik".equalsIgnoreCase(vmName)
                    || System.getenv("POJAV_NATIVEDIR") != null
                    || System.getenv("FCL_NATIVEDIR") != null;
        }
        return android;
    }

    /** FCL 是否在当前进程里（用 POJAV_NATIVEDIR 判定，FCL 一定会设置它）。 */
    public static boolean isFcl() {
        return System.getenv("POJAV_NATIVEDIR") != null;
    }

    public static String describe() {
        return "vm=" + System.getProperty("java.vm.name", "?")
                + ", pojav=" + System.getenv("POJAV_NATIVEDIR")
                + ", fcl=" + System.getenv("FCL_NATIVEDIR");
    }
}
