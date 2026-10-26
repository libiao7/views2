# VR180 SBS OpenGL prototype

Branch: `feature/vr180-sbs-opengl`

## What is implemented

`Vr180SbsGlSurfaceView` is the first rendering-stage prototype for a **VR180 equirectangular video whose source is side-by-side (SBS)**.

- Media3/ExoPlayer can decode directly to the `Surface` exposed by `onVideoSurfaceAvailable`.
- Frames remain in a GPU external texture; the shader remaps them to perspective views without copying frames through CPU memory or pre-transcoding.
- The view draws left and right eyes into the two halves of the display.
- The initial vertical field of view is 83.97 degrees, matching the earlier 1600×1440-per-eye target assumption.

## Not wired into MainActivity yet

This commit intentionally adds the renderer as an isolated component. `MainActivity` currently attaches ExoPlayer to `PlayerView`; changing the output surface and preserving the existing subtitle/controller/gesture behavior should be a separate integration change. Do not assume VR180 playback is active just by building this commit.

The intended ExoPlayer connection is:

```kotlin
vrView.onVideoSurfaceAvailable = { surface ->
    player.setVideoSurface(surface)
}
```

Before destroying the view, detach the player output first:

```kotlin
player.clearVideoSurface()
vrView.releaseRenderer()
```

The player and the view must be coordinated across Activity pause/resume and surface recreation. The current prototype does not yet implement this lifecycle integration.

## Important limitations to validate

1. The shader assumes the source is a 180-degree horizontal equirectangular projection with SBS eye order (left eye first). VR180 files may use different projection metadata, vertical mapping, orientation, or eye order.
2. The initial camera is fixed-forward; head tracking and manual yaw/pitch controls are not implemented.
3. No subtitle composition, audio/UI integration, or renderer fallback is implemented here.
4. Android hardware decoders may reject an 8192×4096 stream even on a fast chipset. Confirm the selected MediaCodec decoder and actual playback on the target phone.
5. Validate the source orientation and UV mapping against a known test pattern before judging image quality. This is an initial implementation, not yet a release-ready player.
