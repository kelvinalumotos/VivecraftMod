package org.vivecraft.mixin.client.blaze3d.opengl;

import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
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
        for (int face = 0; face < 6; face++) {
            for (int level = 0; level < mipLevels; level++) {
                GlStateManager._texImage2D(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level,
                    GlConst.toGlInternalId(format), width >> level, height >> level, 0,
                    GlConst.toGlExternalId(format), GlConst.toGlType(format), null);
                vivecraft$checkCubeMap("glTexImage2D(face " + face + ", level " + level + ")", label, width, height, true);
            }
        }

        GlTexture texture = new GlTexture(usage, label, format, width, height, depthOrLayers, mipLevels, id);
        this.debugLabels.applyLabel(texture);
        cir.setReturnValue(texture);
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

            for (int m = 0; m < mipmapLevels; m++) {
                GlStateManager._texImage2D(GL30C.GL_TEXTURE_2D, m, GlConst.toGlInternalId(textureFormat), width >> m,
                    height >> m, 0, GlConst.toGlExternalId(textureFormat), GlConst.toGlType(textureFormat), null);
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
