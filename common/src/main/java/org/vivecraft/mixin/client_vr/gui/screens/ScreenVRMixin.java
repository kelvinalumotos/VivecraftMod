package org.vivecraft.mixin.client_vr.gui.screens;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.MethodHolder;
import org.vivecraft.client_vr.VRState;

@Mixin(Screen.class)
public abstract class ScreenVRMixin {

    @Shadow
    protected abstract <T extends GuiEventListener & Renderable> T addRenderableWidget(T widget);

    @Unique
    private Button vivecraft$closeButton;

    /**
     * QuestCraft: a close button in the top right corner of every screen that can be closed. Some mod
     * screens have no back or done button, and in VR there is no Escape key at hand (only the left
     * controller's menu button, which not everyone knows about).
     */
    @Inject(method = {"init(II)V", "rebuildWidgets"}, at = @At("TAIL"))
    private void vivecraft$questcraftCloseButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (!VRState.VR_RUNNING || self instanceof TitleScreen || !self.shouldCloseOnEsc()) {
            return;
        }
        // init(II) can run right after rebuildWidgets, don't add it twice
        if (this.vivecraft$closeButton != null && self.children().contains(this.vivecraft$closeButton)) {
            return;
        }
        this.vivecraft$closeButton = Button.builder(Component.literal("X"), button -> self.onClose())
            .bounds(self.width - 24, 4, 20, 20)
            .build();
        this.addRenderableWidget(this.vivecraft$closeButton);
    }

    @Inject(method = {"extractBackground", "extractPanorama", "extractTransparentBackground"}, at = @At("HEAD"), cancellable = true)
    private void vivecraft$vrNoBackground(CallbackInfo ci) {
        if (VRState.VR_RUNNING) {
            if (!ClientDataHolderVR.getInstance().vrSettings.menuBackground &&
                (ClientDataHolderVR.getInstance().menuWorldRenderer.isReady() ||
                    ClientDataHolderVR.getInstance().vrSettings.menuWorldFallbackPanorama ||
                    !MethodHolder.willBeInMenuRoom((Screen) (Object) this)
                ))
            {
                ci.cancel();
            }
        }
    }

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    public void vivecraft$noGuiBlur(CallbackInfo ci) {
        if (VRState.VR_RUNNING) {
            ci.cancel();
        }
    }

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void vivecraft$maybeNoPanorama(CallbackInfo ci) {
        if (VRState.VR_RUNNING && (ClientDataHolderVR.getInstance().menuWorldRenderer.isReady() ||
            ClientDataHolderVR.getInstance().vrSettings.menuWorldFallbackPanorama
        ))
        {
            ci.cancel();
        }
    }
}
