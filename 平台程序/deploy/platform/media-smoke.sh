#!/bin/sh
set -eu

# Run real codec operations under the final service UID; old heif-convert does not support --help.
media_probe_dir=$(mktemp -d)
trap 'rm -rf "$media_probe_dir"' EXIT HUP INT TERM

ffmpeg -nostdin -y -v error -threads 1 -filter_threads 1 -f lavfi -i color=c=green:s=64x48 \
    -frames:v 1 "$media_probe_dir/source.png"
heif-enc -o "$media_probe_dir/source.heic" "$media_probe_dir/source.png" > "$media_probe_dir/heif.log" 2>&1
heif-convert "$media_probe_dir/source.heic" "$media_probe_dir/heic.png"

# Self-generated 64x48 AVIF fixture; Jammy FFmpeg cannot mux AVIF, but its libheif must decode it.
base64 -d > "$media_probe_dir/source.avif" <<'AVIF'
AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAAD5bWV0YQAAAAAAAAAvaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAFBpY3R1cmVIYW5kbGVyAAAAAA5waXRtAAAAAAABAAAAHmlsb2MAAAAARAAAAQABAAAAAQAAASEAAAAiAAAAKGlpbmYAAAAAAAEAAAAaaW5mZQIAAAAAAQAAYXYwMUNvbG9yAAAAAGppcHJwAAAAS2lwY28AAAAUaXNwZQAAAAAAAABAAAAAMAAAABBwaXhpAAAAAAMICAgAAAAMYXYxQ4EADAAAAAATY29scm5jbHgAAgACAAIAAAAAF2lwbWEAAAAAAAAAAQABBAECgwQAAAAqbWRhdAoKAAAAAq/3n/8wCDIUEADAAAACgAAACgV5Um5D1+ZCYyA=
AVIF
heif-convert "$media_probe_dir/source.avif" "$media_probe_dir/avif.png"
test -s "$media_probe_dir/heic.png" && test -s "$media_probe_dir/avif.png"

ffmpeg -nostdin -y -v error -threads 1 -filter_threads 1 -f lavfi -i color=c=blue:s=128x128:r=10 \
    -f lavfi -i sine=frequency=440:sample_rate=44100 -t 0.3 -c:v libx265 \
    -x265-params pools=1:frame-threads=1:log-level=error -tag:v hvc1 -c:a aac "$media_probe_dir/phone.mov"
ffmpeg -nostdin -y -v error -threads 1 -filter_threads 1 -i "$media_probe_dir/phone.mov" \
    -c:v libx264 -pix_fmt yuv420p -threads 1 -c:a aac -ac 2 -ar 48000 "$media_probe_dir/video.mp4"
media_probe_codec=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of default=nw=1:nk=1 "$media_probe_dir/video.mp4")
test "$media_probe_codec" = h264
ffmpeg -nostdin -y -v error -i "$media_probe_dir/video.mp4" -vn -c:a libmp3lame -ac 2 -ar 44100 "$media_probe_dir/audio.mp3"
test -s "$media_probe_dir/audio.mp3"
echo 'Media codecs verified: HEIC/AVIF/HEVC/H264/AAC/MP3'
