package org.vivecraft.mixin.client.blaze3d.opengl;

import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.client.extensions.GlDeviceExtension;
import org.vivecraft.client_vr.settings.VRSettings;

import javax.annotation.Nullable;

@Mixin(GlDevice.class)
public class GlDeviceMixin implements GlDeviceExtension {
    @Shadow
    @Final
    private GlDebugLabel debugLabels;

    /**
     * QuestCraft: cube map creation, step by step. On Quest (OpenGL ES through LTW) creating the title
     * panorama cube map reported GL_INVALID_ENUM, which failed the resource reload and kept VR from
     * starting. This checks the error after every call and logs which one fails. Errors from the optional
     * mip level/LOD parameters are only logged, since the defaults work for these textures; a failing
     * bind or upload still throws like vanilla.
     */
    @Inject(method = "createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;", at = @At("HEAD"), cancellable = true)
    private void vivecraft$questcraftCubeMap(
        @Nullable String label, int usage, TextureFormat format, int width, int height, int depthOrLayers,
        int mipLevels, CallbackInfoReturnable<GpuTexture> cir)
    {
        if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) == 0) {
            return;
        }
        GlStateManager.clearGlErrors();
        int id = GlStateManager._genTexture();
        if (label == null) {
            label = String.valueOf(id);
        }

        GL11.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, id);
        vivecraft$checkCubeMap("glBindTexture(GL_TEXTURE_CUBE_MAP)", label, width, height, true);
        GlStateManager._texParameter(GL13C.GL_TEXTURE_CUBE_MAP, GL30C.GL_TEXTURE_MAX_LEVEL, mipLevels - 1);
        vivecraft$checkCubeMap("glTexParameteri(GL_TEXTURE_MAX_LEVEL)", label, width, height, false);
        GlStateManager._texParameter(GL13C.GL_TEXTURE_CUBE_MAP, GL30C.GL_TEXTURE_MIN_LOD, 0);
        vivecraft$checkCubeMap("glTexParameteri(GL_TEXTURE_MIN_LOD)", label, width, height, false);
        GlStateManager._texParameter(GL13C.GL_TEXTURE_CUBE_MAP, GL30C.GL_TEXTURE_MAX_LOD, mipLevels - 1);
        vivecraft$checkCubeMap("glTexParameteri(GL_TEXTURE_MAX_LOD)", label, width, height, false);
        if (format.hasDepthAspect()) {
            GlStateManager._texParameter(GL13C.GL_TEXTURE_CUBE_MAP, GL30C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_NONE);
            vivecraft$checkCubeMap("glTexParameteri(GL_TEXTURE_COMPARE_MODE)", label, width, height, false);
        }
        // LTW wraps glTexImage2D and rejects the cube map face targets with GL_INVALID_ENUM, but it does not
        // wrap glTexStorage2D, which allocates all faces at once and is core in OpenGL ES 3.0
        boolean allocated = false;
        try {
            long texStorage2D = vivecraft$findTexStorage2D();
            if (texStorage2D != 0L) {
                JNI.invokeV(GL13C.GL_TEXTURE_CUBE_MAP, mipLevels, GlConst.toGlInternalId(format), width, height, texStorage2D);
                int error = GlStateManager._getError();
                if (error == 0) {
                    allocated = true;
                } else {
                    VRSettings.LOGGER.warn("QuestCraft: OpenGL error {} in glTexStorage2D while creating cube map '{}', falling back to glTexImage2D", error, label);
                }
            } else {
                VRSettings.LOGGER.warn("QuestCraft: glTexStorage2D not found, creating cube map '{}' with glTexImage2D", label);
            }
        } catch (Throwable t) {
            VRSettings.LOGGER.warn("QuestCraft: glTexStorage2D failed for cube map '{}', falling back to glTexImage2D", label, t);
        }
        if (!allocated) {
            for (int face = 0; face < 6; face++) {
                for (int level = 0; level < mipLevels; level++) {
                    GlStateManager._texImage2D(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level,
                        GlConst.toGlInternalId(format), width >> level, height >> level, 0,
                        GlConst.toGlExternalId(format), GlConst.toGlType(format), null);
                    vivecraft$checkCubeMap("glTexImage2D(face " + face + ", level " + level + ")", label, width, height, true);
                }
            }
        }

        GlTexture texture = new GlTexture(usage, label, format, width, height, depthOrLayers, mipLevels, id);
        this.debugLabels.applyLabel(texture);
        cir.setReturnValue(texture);
    }

    /** whether the texture bound to GL_TEXTURE_2D already has immutable storage */
    @Unique
    private static boolean vivecraft$isImmutable(String label) {
        int earlier = GlStateManager._getError();
        if (earlier != 0) {
            VRSettings.LOGGER.warn("QuestCraft: OpenGL error {} setting up '{}' before the storage check", earlier, label);
        }
        // GL_TEXTURE_IMMUTABLE_FORMAT, core in OpenGL 4.2 and OpenGL ES 3.0
        int immutable = GL11C.glGetTexParameteri(GL30C.GL_TEXTURE_2D, 0x912F);
        int error = GlStateManager._getError();
        if (error != 0) {
            VRSettings.LOGGER.warn("QuestCraft: could not query GL_TEXTURE_IMMUTABLE_FORMAT for '{}' (error {})", label, error);
            return false;
        }
        if (immutable != 0) {
            VRSettings.LOGGER.info("QuestCraft: '{}' already has immutable storage, using it as is", label);
        }
        return immutable != 0;
    }

    @Unique
    private static long vivecraft$texStorage2D = -1L;

    /**
     * LTW reports OpenGL 3.0, so LWJGL never loads glTexStorage2D (GL 4.2) into the capabilities, even though
     * the Quest's OpenGL ES 3 driver has it. Look it up through LWJGL's provider first, then in the driver.
     */
    @Unique
    private static long vivecraft$findTexStorage2D() {
        if (vivecraft$texStorage2D != -1L) {
            return vivecraft$texStorage2D;
        }
        long address = 0L;
        String source = "none";
        try {
            address = GL.getCapabilities().glTexStorage2D;
            source = "GL capabilities";
        } catch (Throwable ignored) {}
        if (address == 0L) {
            try {
                FunctionProvider provider = GL.getFunctionProvider();
                if (provider != null) {
                    address = provider.getFunctionAddress("glTexStorage2D");
                    source = "GL function provider";
                }
            } catch (Throwable ignored) {}
        }
        if (address == 0L) {
            try {
                address = Library.loadNative("org.lwjgl.opengl", "/system/lib64/libGLESv3.so")
                    .getFunctionAddress("glTexStorage2D");
                source = "libGLESv3.so";
            } catch (Throwable t) {
                VRSettings.LOGGER.warn("QuestCraft: could not open libGLESv3.so", t);
            }
        }
        VRSettings.LOGGER.info("QuestCraft: glTexStorage2D {}", address != 0L ? "found in " + source : "not found");
        vivecraft$texStorage2D = address;
        return address;
    }

    @Unique
    private static void vivecraft$checkCubeMap(String call, String label, int width, int height, boolean required) {
        int error = GlStateManager._getError();
        if (error == 0) {
            return;
        }
        VRSettings.LOGGER.warn("QuestCraft: OpenGL error {} in {} while creating cube map '{}' ({}x{}){}",
            error, call, label, width, height, required ? "" : ", ignored");
        if (required) {
            if (error == GL30C.GL_OUT_OF_MEMORY) {
                throw new GpuOutOfMemoryException("Could not allocate texture of " + width + "x" + height + " for " + label);
            }
            throw new IllegalStateException("OpenGL error " + error + " in " + call);
        }
    }

    /**
     * copy of {@link GlDevice#createTexture(String, int, TextureFormat, int, int, int, int)} but with a fixes texture id
     */
    @Override
    public GpuTexture vivecraft$createFixedIdTexture(
        @Nullable String label, int usageFlags, TextureFormat textureFormat, int width,
        int height, int depthLayers, int mipmapLevels, int texId)
    {
        if (mipmapLevels < 1) {
            throw new IllegalArgumentException("mipLevels must be at least 1");
        } else if (depthLayers < 1) {
            throw new IllegalArgumentException("depthOrLayers must be at least 1");
        } else {
            if ((usageFlags & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
                throw new UnsupportedOperationException("CubeMap textures are not supported");
            } else if (depthLayers > 1) {
                throw new UnsupportedOperationException("Array or 3D textures are not supported");
            }

            GlStateManager.clearGlErrors();
            if (label == null) {
                label = String.valueOf(texId);
            }

            GlStateManager._bindTexture(texId);
            GlStateManager._texParameter(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MAX_LEVEL, mipmapLevels - 1);
            GlStateManager._texParameter(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MIN_LOD, 0);
            GlStateManager._texParameter(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MAX_LOD, mipmapLevels - 1);
            if (textureFormat.hasDepthAspect()) {
                GlStateManager._texParameter(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_NONE);
            }

            // QuestCraft: OpenXR swapchain images come from the runtime with immutable storage
            // (glTexStorage2D). Re-allocating them with glTexImage2D is GL_INVALID_OPERATION on OpenGL ES,
            // so only allocate textures that do not have storage yet.
            if (!vivecraft$isImmutable(label)) {
                for (int m = 0; m < mipmapLevels; m++) {
                    GlStateManager._texImage2D(GL30C.GL_TEXTURE_2D, m, GlConst.toGlInternalId(textureFormat), width >> m,
                        height >> m, 0, GlConst.toGlExternalId(textureFormat), GlConst.toGlType(textureFormat), null);
                }
            }

            int error = GlStateManager._getError();
            if (error == GL30C.GL_OUT_OF_MEMORY) {
                throw new GpuOutOfMemoryException(
                    "Could not allocate texture of " + width + "x" + height + " for " + label);
            } else if (error != 0) {
                throw new IllegalStateException("OpenGL error " + error);
            } else {
                GlTexture glTexture = new GlTexture(usageFlags, label, textureFormat, width, height, depthLayers,
                    mipmapLevels, texId);
                this.debugLabels.applyLabel(glTexture);
                return glTexture;
            }
        }
    }
}
