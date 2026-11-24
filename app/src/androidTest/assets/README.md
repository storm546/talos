`reordered.mp4` is a synthetic H.264 fixture, generated from FFmpeg's test pattern. Its
decode order crosses the 10-second cutoff before returning to two earlier presentation timestamps.

```sh
ffmpeg -f lavfi -i 'testsrc2=size=128x96:rate=12:duration=10.5' -c:v libx264 -preset fast \
  -crf 34 -threads 1 -g 48 -bf 2 -x264-params 'b-adapt=0:scenecut=0' reordered.mp4
```

`slowmotion.mp4` is a synthetic 2 s, 30 fps H.264 clip whose container says it was captured at 120 fps, as a phone's
slow-motion video stored at the playback rate would. FFmpeg writes `com.android.capture.fps` as a string under
`moov/udta/meta`; Android's MP4 extractor reads it only as a float under `moov/meta`, so a short script moves the box
and stores the float (the script is kept with the Wave 6 logs as `logs/wave6/app/patch_capture_fps.py`).

```sh
ffmpeg -f lavfi -i 'testsrc2=size=128x96:rate=30:duration=2' -c:v libx264 -preset fast -crf 34 -threads 1 \
  -movflags use_metadata_tags -metadata com.android.capture.fps=ABCD slomo-raw.mp4
python3 patch_capture_fps.py slomo-raw.mp4 slowmotion.mp4 120
```
