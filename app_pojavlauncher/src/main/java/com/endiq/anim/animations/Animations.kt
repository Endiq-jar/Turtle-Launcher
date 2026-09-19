package com.endiq.anim.animations

import com.endiq.anim.animations.bounce.BounceEnlargeAnimator
import com.endiq.anim.animations.bounce.BounceInDownAnimator
import com.endiq.anim.animations.bounce.BounceInLeftAnimator
import com.endiq.anim.animations.bounce.BounceInRightAnimator
import com.endiq.anim.animations.bounce.BounceInUpAnimator
import com.endiq.anim.animations.bounce.BounceShrinkAnimator
import com.endiq.anim.animations.fade.FadeInAnimator
import com.endiq.anim.animations.fade.FadeInDownAnimator
import com.endiq.anim.animations.fade.FadeInLeftAnimator
import com.endiq.anim.animations.fade.FadeInRightAnimator
import com.endiq.anim.animations.fade.FadeInUpAnimator
import com.endiq.anim.animations.fade.FadeOutAnimator
import com.endiq.anim.animations.fade.FadeOutDownAnimator
import com.endiq.anim.animations.fade.FadeOutLeftAnimator
import com.endiq.anim.animations.fade.FadeOutRightAnimator
import com.endiq.anim.animations.fade.FadeOutUpAnimator
import com.endiq.anim.animations.other.PulseAnimator
import com.endiq.anim.animations.other.ShakeAnimator
import com.endiq.anim.animations.other.WobbleAnimator
import com.endiq.anim.animations.slide.SlideInDownAnimator
import com.endiq.anim.animations.slide.SlideInLeftAnimator
import com.endiq.anim.animations.slide.SlideInRightAnimator
import com.endiq.anim.animations.slide.SlideInUpAnimator
import com.endiq.anim.animations.slide.SlideOutDownAnimator
import com.endiq.anim.animations.slide.SlideOutLeftAnimator
import com.endiq.anim.animations.slide.SlideOutRightAnimator
import com.endiq.anim.animations.slide.SlideOutUpAnimator
import com.endiq.anim.animations.zoom.ZoomInAnimator
import com.endiq.anim.animations.zoom.ZoomInDownAnimator
import com.endiq.anim.animations.zoom.ZoomInLeftAnimator
import com.endiq.anim.animations.zoom.ZoomInRightAnimator
import com.endiq.anim.animations.zoom.ZoomInUpAnimator
import com.endiq.anim.animations.zoom.ZoomOutAnimator
import com.endiq.anim.animations.zoom.ZoomOutDownAnimator
import com.endiq.anim.animations.zoom.ZoomOutLeftAnimator
import com.endiq.anim.animations.zoom.ZoomOutRightAnimator
import com.endiq.anim.animations.zoom.ZoomOutUpAnimator
import com.endiq.anim.animations.sheet.SheetInAnimator
import com.endiq.anim.animations.sheet.SheetOutAnimator


enum class Animations(val animator: BaseAnimator) {
    //Bounce
    BounceInDown(BounceInDownAnimator()),
    BounceInLeft(BounceInLeftAnimator()),
    BounceInRight(BounceInRightAnimator()),
    BounceInUp(BounceInUpAnimator()),
    BounceEnlarge(BounceEnlargeAnimator()),
    BounceShrink(BounceShrinkAnimator()),

    //Fade in
    FadeIn(FadeInAnimator()),
    FadeInLeft(FadeInLeftAnimator()),
    FadeInRight(FadeInRightAnimator()),
    FadeInUp(FadeInUpAnimator()),
    FadeInDown(FadeInDownAnimator()),

    //Fade out
    FadeOut(FadeOutAnimator()),
    FadeOutLeft(FadeOutLeftAnimator()),
    FadeOutRight(FadeOutRightAnimator()),
    FadeOutUp(FadeOutUpAnimator()),
    FadeOutDown(FadeOutDownAnimator()),

    //Slide in
    SlideInLeft(SlideInLeftAnimator()),
    SlideInRight(SlideInRightAnimator()),
    SlideInUp(SlideInUpAnimator()),
    SlideInDown(SlideInDownAnimator()),

    //Slide out
    SlideOutLeft(SlideOutLeftAnimator()),
    SlideOutRight(SlideOutRightAnimator()),
    SlideOutUp(SlideOutUpAnimator()),
    SlideOutDown(SlideOutDownAnimator()),


    //Zoom - the animation library shipped with no zoom animators at all, so these are new
    // (see ZoomInAnimator et al). Direction variants also drift in from that side.
    ZoomIn(ZoomInAnimator()),
    ZoomInDown(ZoomInDownAnimator()),
    ZoomInLeft(ZoomInLeftAnimator()),
    ZoomInRight(ZoomInRightAnimator()),
    ZoomInUp(ZoomInUpAnimator()),
    ZoomOut(ZoomOutAnimator()),
    ZoomOutDown(ZoomOutDownAnimator()),
    ZoomOutLeft(ZoomOutLeftAnimator()),
    ZoomOutRight(ZoomOutRightAnimator()),
    ZoomOutUp(ZoomOutUpAnimator()),
    SheetIn(SheetInAnimator()),
    SheetOut(SheetOutAnimator()),

    //Other
    Pulse(PulseAnimator()),
    Wobble(WobbleAnimator()),
    Shake(ShakeAnimator())
}