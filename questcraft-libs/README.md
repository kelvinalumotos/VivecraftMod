# questcraft-libs

`lwjgl-openxr-3.4.1.jar` is LWJGL's OpenXR binding built from
[kelvinalumotos/lwjgl3 `questcraft/3.4.1`](https://github.com/kelvinalumotos/lwjgl3/tree/questcraft/3.4.1)
(CI run 41683156404). Stock LWJGL does not generate the Android/OpenGL ES extensions QuestCraft
needs on Quest: `XR_KHR_android_create_instance`, `XR_KHR_loader_init_android` and
`XR_KHR_opengl_es_enable`.

It is resolved as `questcraft:lwjgl-openxr:3.4.1` through the `QuestCraftLibs` flatDir repository
in `build.gradle`. Its version must match the LWJGL core Minecraft runs on (3.4.1 for 26.1).
