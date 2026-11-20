`reordered.mp4` is a synthetic H.264 fixture, generated from FFmpeg's test pattern. Its
decode order crosses the 10-second cutoff before returning to two earlier presentation timestamps.

```sh
ffmpeg -f lavfi -i 'testsrc2=size=128x96:rate=12:duration=10.5' -c:v libx264 -preset fast \
  -crf 34 -threads 1 -g 48 -bf 2 -x264-params 'b-adapt=0:scenecut=0' reordered.mp4
```
